// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.kvutils

// define the different package validation modes.
sealed abstract class PackageValidationMode extends Product with Serializable

object PackageValidationMode {
  // Specify that the package committer should validate the packages
  // before committing them to the ledger.
  case object Precommit extends PackageValidationMode

  // Specify that the engine should validate the packages every time
  // it load them from the ledger.
  // This mode is useful for ledger integration that cannot handle
  // long-running submissions (> 10s).
  // See PackageCommitter for more detail.
  case object Postcommit extends PackageValidationMode

  // Specify that neither the engine not the package committer
  // should try to validate the package.
  // This should be use only by non distributed ledger, like DAML-on-SQL
  case object NoValidation extends PackageValidationMode
}
