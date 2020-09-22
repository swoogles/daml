// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.kvutils.committer

import java.util.concurrent.Executors

import com.daml.daml_lf_dev.DamlLf.Archive
import com.daml.ledger.participant.state.kvutils.Conversions.packageUploadDedupKey
import com.daml.ledger.participant.state.kvutils.DamlKvutils._
import com.daml.ledger.participant.state.kvutils.PackageValidationMode
import com.daml.ledger.participant.state.kvutils.committer.Committer.{
  StepInfo,
  buildLogEntryWithOptionalRecordTime
}
import com.daml.lf.archive.Decode
import com.daml.lf.data.Ref
import com.daml.lf.data.Time.Timestamp
import com.daml.lf.engine.Engine
import com.daml.metrics.Metrics

import scala.collection.JavaConverters._
import scala.util.Try

private[kvutils] class PackageCommitter(
    engine: Engine,
    packageValidation: PackageValidationMode,
    override protected val metrics: Metrics,
) extends Committer[DamlPackageUploadEntry.Builder] {

  override protected val committerName = "package_upload"

  metrics.daml.kvutils.committer.packageUpload.loadedPackages(() =>
    engine.compiledPackages().packageIds.size)

  private def rejectionTraceLog(
      msg: String,
      packageUploadEntry: DamlPackageUploadEntry.Builder,
  ): Unit =
    logger.trace(
      s"Package upload rejected, $msg, correlationId=${packageUploadEntry.getSubmissionId}")

  private val authorizeSubmission: Step = (ctx, uploadEntry) => {
    if (ctx.getParticipantId == uploadEntry.getParticipantId) {
      StepContinue(uploadEntry)
    } else {
      val msg =
        s"participant id ${uploadEntry.getParticipantId} did not match authenticated participant id ${ctx.getParticipantId}"
      rejectionTraceLog(msg, uploadEntry)
      reject(
        ctx.getRecordTime,
        uploadEntry.getSubmissionId,
        uploadEntry.getParticipantId,
        _.setParticipantNotAuthorized(
          ParticipantNotAuthorized.newBuilder
            .setDetails(msg))
      )
    }
  }

  // Full package validation.
  // The integrations using kvutils  hould handle long-running submissions (> 10s).
  private[this] val fullValidateEntry: Step = { (ctx, uploadEntry) =>
    validatePackages(uploadEntry.getSubmissionId, uploadEntry.getArchivesList.iterator().asScala) match {
      case Right(_) =>
        StepContinue(uploadEntry)
      case Left(errMsg) =>
        rejectionTraceLog(errMsg, uploadEntry)
        reject(
          ctx.getRecordTime,
          uploadEntry.getSubmissionId,
          uploadEntry.getParticipantId,
          _.setInvalidPackage(Invalid.newBuilder.setDetails(errMsg))
        )
    }
  }

  private val minimalValidateEntry: Step = (ctx, uploadEntry) => {
    // Minimal validation for integrations using kvutils that cannot handle long-running submissions.
    // The package should be decoded and validation in background using [[enqueueFullValidation]].
    val archives = uploadEntry.getArchivesList.asScala
    val errors = if (archives.nonEmpty) {
      archives.foldLeft(List.empty[String]) { (errors, archive) =>
        if (archive.getHashBytes.size > 0 && archive.getPayload.size > 0)
          errors
        else
          s"Invalid archive ${archive.getHash}" :: errors
      }
    } else {
      List("No archives in package")
    }
    if (errors.isEmpty) {
      StepContinue(uploadEntry)
    } else {
      val msg = errors.mkString(", ")
      rejectionTraceLog(msg, uploadEntry)
      reject(
        ctx.getRecordTime,
        uploadEntry.getSubmissionId,
        uploadEntry.getParticipantId,
        _.setInvalidPackage(
          Invalid.newBuilder
            .setDetails(msg)
        )
      )
    }
  }

  private val deduplicateSubmission: Step = (ctx, uploadEntry) => {
    val submissionKey = packageUploadDedupKey(ctx.getParticipantId, uploadEntry.getSubmissionId)
    if (ctx.get(submissionKey).isEmpty) {
      StepContinue(uploadEntry)
    } else {
      val msg = s"duplicate submission='${uploadEntry.getSubmissionId}'"
      rejectionTraceLog(msg, uploadEntry)
      reject(
        ctx.getRecordTime,
        uploadEntry.getSubmissionId,
        uploadEntry.getParticipantId,
        _.setDuplicateSubmission(Duplicate.newBuilder.setDetails(msg))
      )
    }
  }

  private val filterDuplicates: Step = (ctx, uploadEntry) => {
    val archives = uploadEntry.getArchivesList.asScala.filter { archive =>
      val stateKey = DamlStateKey.newBuilder
        .setPackageId(archive.getHash)
        .build
      ctx.get(stateKey).isEmpty
    }
    StepContinue(uploadEntry.clearArchives().addAllArchives(archives.asJava))
  }

  private val preloadExecutor = {
    Executors.newSingleThreadExecutor((runnable: Runnable) => {
      val t = new Thread(runnable)
      t.setDaemon(true)
      t
    })
  }

  private[this] val enqueueFullValidation: Step = { (_, uploadEntry) =>
    preloadExecutor.execute { () =>
      validatePackages(uploadEntry.getSubmissionId, uploadEntry.getArchivesList.asScala.iterator)
      ()
    }
    StepContinue(uploadEntry)
  }

  private[committer] val buildLogEntry: Step = (ctx, uploadEntry) => {
    metrics.daml.kvutils.committer.packageUpload.accepts.inc()
    logger.trace(
      s"Packages committed, packages=[${uploadEntry.getArchivesList.asScala.map(_.getHash).mkString(", ")}] correlationId=${uploadEntry.getSubmissionId}")

    uploadEntry.getArchivesList.forEach { archive =>
      ctx.set(
        DamlStateKey.newBuilder.setPackageId(archive.getHash).build,
        DamlStateValue.newBuilder.setArchive(archive).build
      )
    }
    ctx.set(
      packageUploadDedupKey(ctx.getParticipantId, uploadEntry.getSubmissionId),
      DamlStateValue.newBuilder
        .setSubmissionDedup(DamlSubmissionDedupValue.newBuilder)
        .build
    )
    val successLogEntry =
      buildLogEntryWithOptionalRecordTime(ctx.getRecordTime, _.setPackageUploadEntry(uploadEntry))
    if (ctx.preExecute) {
      setOutOfTimeBoundsLogEntry(uploadEntry, ctx)
    }
    StepStop(successLogEntry)
  }

  private def setOutOfTimeBoundsLogEntry(
      uploadEntry: DamlPackageUploadEntry.Builder,
      commitContext: CommitContext): Unit = {
    commitContext.outOfTimeBoundsLogEntry = Some(
      buildRejectionLogEntry(
        recordTime = None,
        uploadEntry.getSubmissionId,
        uploadEntry.getParticipantId,
        identity)
    )
  }

  override protected def init(
      ctx: CommitContext,
      submission: DamlSubmission,
  ): DamlPackageUploadEntry.Builder =
    submission.getPackageUploadEntry.toBuilder

  override protected val steps: Iterable[(StepInfo, Step)] =
    packageValidation match {
      case PackageValidationMode.NoValidation =>
        Iterable(
          "deduplicate_submission" -> deduplicateSubmission,
          "filter_duplicates" -> filterDuplicates,
          "build_log_entry" -> buildLogEntry
        )
      case PackageValidationMode.Precommit =>
        Iterable(
          "authorize_submission" -> authorizeSubmission,
          "validate_entry" -> fullValidateEntry,
          "deduplicate_submission" -> deduplicateSubmission,
          "filter_duplicates" -> filterDuplicates,
          "build_log_entry" -> buildLogEntry
        )
      case PackageValidationMode.Postcommit =>
        Iterable(
          "authorize_submission" -> authorizeSubmission,
          "minimal_validate_entry" -> minimalValidateEntry,
          "deduplicate_submission" -> deduplicateSubmission,
          "filter_duplicates" -> filterDuplicates,
          "enqueue_full_validation" -> enqueueFullValidation,
          "build_log_entry" -> buildLogEntry
        )
    }

  private def reject[PartialResult](
      recordTime: Option[Timestamp],
      submissionId: String,
      participantId: String,
      addErrorDetails: DamlPackageUploadRejectionEntry.Builder => DamlPackageUploadRejectionEntry.Builder,
  ): StepResult[PartialResult] = {
    metrics.daml.kvutils.committer.packageUpload.rejections.inc()
    StepStop(buildRejectionLogEntry(recordTime, submissionId, participantId, addErrorDetails))
  }

  private[committer] def buildRejectionLogEntry(
      recordTime: Option[Timestamp],
      submissionId: String,
      participantId: String,
      addErrorDetails: DamlPackageUploadRejectionEntry.Builder => DamlPackageUploadRejectionEntry.Builder,
  ): DamlLogEntry = {
    buildLogEntryWithOptionalRecordTime(
      recordTime,
      _.setPackageUploadRejectionEntry(
        addErrorDetails(
          DamlPackageUploadRejectionEntry.newBuilder
            .setSubmissionId(submissionId)
            .setParticipantId(participantId)
        )
      )
    )
  }

  private[this] def validatePackages(
      submissionId: String,
      archives: Iterator[Archive],
  ): Either[String, Unit] = {
    val ctx = metrics.daml.kvutils.committer.packageUpload.validateTimer.time()
    def trace(msg: String): Unit = logger.trace(s"$msg, correlationId=$submissionId")
    val result = for {
      packages <- Try {
        val loadedPackages = engine.compiledPackages().packageIds
        metrics.daml.kvutils.committer.packageUpload.decodeTimer.time { () =>
          archives
            .filterNot(
              a =>
                Ref.PackageId
                  .fromString(a.getHash)
                  .fold(_ => false, loadedPackages.contains))
            .map { archive =>
              Decode.readArchiveAndVersion(archive)._1
            }
            .toMap
        }
      }.toEither.left.map(_.getMessage)
      _ = trace(s"validating engine with ${packages.size} new packages")
      _ <- engine.validatePackages(packages).left.map(_.msg)
      _ = trace("validate complete")
    } yield ()

    result.left.foreach(errMsg =>
      logger.error(s"validate exception, correlationId=$submissionId error='$errMsg'"))

    ctx.close()

    result
  }

}
