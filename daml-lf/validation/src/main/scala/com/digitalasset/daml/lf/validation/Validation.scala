// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.validation

import com.daml.lf.VersionRange
import com.daml.lf.data.Ref.{ModuleName, PackageId}
import com.daml.lf.language.Ast.{Module, Package}
import com.daml.lf.language.LanguageVersion
import com.daml.lf.transaction.VersionTimeline

object Validation {

  private def runSafely[X](x: => X): Either[ValidationError, X] =
    try {
      Right(x)
    } catch {
      case e: ValidationError => Left(e)
    }

  def checkPackages(
      pkgs: Map[PackageId, Package],
      allowedLangVersions: VersionRange[LanguageVersion],
  ): Either[ValidationError, Unit] =
    runSafely {
      val world = new World(pkgs)
      pkgs.foreach {
        case (pkgId, pkg) => unsafeCheckPackage(world, allowedLangVersions, pkgId, pkg)
      }
    }

  def checkPackage(
      pkgs: PartialFunction[PackageId, Package],
      pkgId: PackageId,
      allowedLangVersions: VersionRange[LanguageVersion] = VersionTimeline.stableLanguageVersions,
  ): Either[ValidationError, Unit] =
    runSafely {
      val world = new World(pkgs)
      unsafeCheckPackage(world, allowedLangVersions, pkgId, world.lookupPackage(NoContext, pkgId))
    }

  private def unsafeCheckPackage(
      world: World,
      allowedLangVersions: VersionRange[LanguageVersion],
      pkgId: PackageId,
      pkg: Package,
  ): Unit = {
    if (!allowedLangVersions.contains(pkg.languageVersion))
      throw EDisallowedLanguageVersion(pkgId, pkg.languageVersion, allowedLangVersions)
    Collision.checkPackage(pkgId, pkg)
    Recursion.checkPackage(pkgId, pkg)
    DependencyVersion.checkPackage(world, pkgId, pkg)
    pkg.modules.values.foreach(unsafeCheckModule(world, pkgId, _))
  }

  def checkModule(
      pkgs: PartialFunction[PackageId, Package],
      pkgId: PackageId,
      modName: ModuleName,
  ): Either[ValidationError, Unit] =
    runSafely {
      val world = new World(pkgs)
      unsafeCheckModule(world, pkgId, world.lookupModule(NoContext, pkgId, modName))
    }

  private def unsafeCheckModule(world: World, pkgId: PackageId, mod: Module): Unit = {
    Typing.checkModule(world, pkgId, mod)
    Serializability.checkModule(world, pkgId, mod)
    PartyLiterals.checkModule(world, pkgId, mod)
  }
}
