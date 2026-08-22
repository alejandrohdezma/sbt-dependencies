/*
 * Copyright 2025-2026 Alejandro Hernández <https://github.com/alejandrohdezma>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alejandrohdezma.sbt.dependencies.finders

import java.util.concurrent.Executors

import scala.Console._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import sbt.librarymanagement.CrossVersion
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.finders.AgeChecker.TooRecent
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version._
import com.alejandrohdezma.sbt.dependencies.model.DependencyOps._
import com.alejandrohdezma.sbt.dependencies.model.ScalaVersion

/** Utility functions for dependency resolution. */
object Utils {

  /** Resolves the latest version for each annotated dependency in parallel, logging updates, honoring each dependency's
    * `scala-filter` annotation.
    *
    * Dependencies whose filter matches the current axis (`finders.scalaVersion`) resolve with the in-scope `Finders`.
    * Dependencies filtered to another axis resolve against the first `finders.crossScalaVersions` entry matching the
    * filter, so a Scala-2-only artifact in a project whose default axis is Scala 3 still gets a real update check
    * instead of a failed `name_3` lookup. A filter matching no cross version falls back to the current axis (yielding a
    * "Could not resolve" warning). The built-in `{{scala}}` variable resolves to the project's default `scalaVersion`,
    * so for deps moved to another axis its resolved value is rebound to that axis's version before the lookup.
    *
    * Returns the list of dependencies with their versions updated where applicable. Each dep's `(configuration,
    * crossVersion)` drives the Maven module shape lookup, so per-patch-published artifacts (`name_2.13.16`) and
    * per-binary-published ones (`name_2.13`) are routed to the right coordinate.
    */
  def resolveLatestVersions(deps: List[Dependency], parallelism: Int)(implicit
      finders: Finders,
      logger: Logger
  ): List[Dependency] = {
    val executor = Executors.newFixedThreadPool(parallelism)

    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)

    val futures = deps.map { dep =>
      val scalaVersion = dep.scalaFilter
        .filterNot(finders.scalaVersion.value.startsWith(_))
        .flatMap(filter => finders.crossScalaVersions.find(_.startsWith(filter)))
        .map(ScalaVersion(_))

      Future {
        val axisFinders: Finders = scalaVersion.fold(finders)(finders.withScalaVersion)

        val bound = scalaVersion.fold(dep)(rebindScalaVariable(dep, _))

        (bound, findLatestVersion(bound)(using axisFinders, logger))
      }
    }

    val updated = futures.map { future =>
      Await.result(future, Duration.Inf) match {
        // Exact-pinned (`=`) numeric: never update, even if a newer version exists.
        case (original @ Dependency.Version(num: Numeric), _) if num.marker.isExact =>
          logger.info(s" ↳ $CYAN⊙$RESET $CYAN${original.toLine}$RESET")

          original

        // Numeric with an artifact migration: org/name moved, take the latest at the new coordinates.
        case (original @ Dependency.Version(_: Numeric), latest) if !latest.isSameArtifact(original) =>
          logger.info(s" ↳ $YELLOW⇄$RESET $YELLOW${original.toLine}$RESET -> $CYAN${latest.toLine}$RESET")

          latest

        // Numeric already at the latest allowed version: keep as-is.
        case (original @ Dependency.Version(num: Numeric), Dependency.Version(latest: Numeric))
            if latest.isSameVersion(num) =>
          logger.info(s" ↳ $GREEN✓$RESET $GREEN${original.toLine}$RESET")

          original

        // Numeric with a newer version available within the marker constraints: bump.
        case (original @ Dependency.Version(_: Numeric), latest) =>
          logger.info(s" ↳ $YELLOW⬆$RESET $YELLOW${original.toLine}$RESET -> $CYAN${latest.version.show}$RESET")

          latest

        // Variable already at latest, but the artifact has migrated — surface the migration without changing the line
        // (the variable is build-managed; the user resolves the migration themselves).
        case (original @ Dependency.Version(variable: Variable), latest)
            if !latest.isSameArtifact(original) && variable.isSameVersion(latest.version) =>
          logger.info {
            s" ↳ $GREEN✓$RESET $GREEN${original.toLine}$RESET (resolves to `$variable`), migration " +
              s"to ${latest.organization}:${latest.name} available"
          }

          original

        // Variable with both a newer version and a migration available — surface both, keep the line unchanged.
        case (original @ Dependency.Version(_: Variable), latest) if !latest.isSameArtifact(original) =>
          logger.info {
            s" ↳ $CYAN⊸$RESET $CYAN${original.toLine}$RESET (resolves to `${original.version}`, " +
              s"latest: `$YELLOW${latest.version}$RESET`, migration to" +
              s" ${latest.organization}:${latest.name} available)"
          }

          original

        // Variable resolved to the current latest version: keep as-is.
        case (original @ Dependency.Version(variable: Variable), latest) if variable.isSameVersion(latest.version) =>
          logger.info(s" ↳ $GREEN✓$RESET $GREEN${original.toLine}$RESET (resolves to `$variable`)")

          original

        // BOM-managed (`*`) at the BOM-pinned version, but the artifact has migrated — surface the migration without
        // changing the line (the BOM governs the version; the user resolves the migration in the BOM or the conf).
        case (original @ Dependency.Version(bom: Bom), latest)
            if !latest.isSameArtifact(original) && bom.isSameVersion(latest.version) =>
          logger.info {
            s" ↳ $GREEN✓$RESET $GREEN${original.toLine}$RESET (resolves to `$bom` via BOM), migration " +
              s"to ${latest.organization}:${latest.name} available"
          }

          original

        // BOM-managed (`*`) with both a newer version and a migration available — surface both, keep the line unchanged.
        case (original @ Dependency.Version(bom: Bom), latest) if !latest.isSameArtifact(original) =>
          logger.info {
            s" ↳ $CYAN⊸$RESET $CYAN${original.toLine}$RESET (resolves to `$bom` via BOM, " +
              s"latest: `$YELLOW${latest.version}$RESET`, migration to ${latest.organization}:${latest.name} available)"
          }

          original

        // BOM-managed (`*`) at the BOM-pinned version, which is also the latest available: keep as-is.
        case (original @ Dependency.Version(bom: Bom), latest) if bom.isSameVersion(latest.version) =>
          logger.info(s" ↳ $GREEN✓$RESET $GREEN${original.toLine}$RESET (resolves to `$bom` via BOM)")

          original

        // BOM-managed (`*`) without BOM context (e.g. group-level commands): keep, the BOMs govern the version.
        case (original @ Dependency.Version(Bom(None)), _) =>
          logger.info(s" ↳ $CYAN⊙$RESET $CYAN${original.toLine}$RESET (follows the group's BOMs)")

          original

        // BOM-managed (`*`) behind the latest available: log the gap, keep the line (updating means bumping the BOM).
        case (original @ Dependency.Version(bom: Bom), latest) =>
          logger.info {
            s" ↳ $CYAN⊸$RESET $CYAN${original.toLine}$RESET (resolves to `$bom` via BOM, " +
              s"latest: `$YELLOW${latest.version}$RESET`)"
          }

          original

        // Variable behind the latest: log the gap, leave the line untouched (the user updates the variable in build.sbt).
        case (original, latest) =>
          logger.info {
            s" ↳ $CYAN⊸$RESET $CYAN${original.toLine}$RESET (resolves to `${original.version}`, " +
              s"latest: `$YELLOW${latest.version}$RESET`)"
          }

          original
      }
    }

    executor.shutdown()
    updated
  }

  /** Rebinds the built-in `{{scala}}` variable's resolved version to the given Scala version, keeping its marker. The
    * variable is resolved against the project's default `scalaVersion` at read time, so deps filtered to another axis
    * carry a resolution from the wrong axis until rebound.
    */
  private def rebindScalaVariable(dep: Dependency, scalaVersion: ScalaVersion): Dependency =
    (dep.version, scalaVersion) match {
      case (Variable("scala", Some(resolved)), ScalaVersion(Numeric(version))) =>
        dep.withVersion(Variable("scala", Some(version.withMarker(resolved.marker))))

      case _ => dep
    }

  /** Finds the latest version of a dependency based on the current version's marker.
    *
    * The marker controls the update scope:
    *   - `=` (Exact): No updates, returns current version
    *   - `~` (Minor): Updates within same major.minor
    *   - `^` (Major): Updates within same major
    *
    * For variable versions, delegates to the resolved numeric version. The Maven module shape used for the lookup is
    * derived from the dep's `(configuration, crossVersion)` — see [[VersionFinder.findVersions]].
    *
    * @param dependency
    *   The dependency to find the latest version for.
    * @return
    *   A `Dependency` with `version: Version.Numeric` containing the latest resolved version, preserving the original
    *   marker.
    */
  def findLatestVersion(dependency: Dependency)(implicit
      finders: Finders,
      logger: Logger
  ): Dependency = {
    (dependency.version, finders.migrationFinder.findMigration(dependency)) match {
      // Variable: resolve to its underlying Numeric and recurse — fail loudly if the variable was never resolved.
      case (variable: Dependency.Version.Variable, _) =>
        variable.resolved
          .map(num => findLatestVersion(dependency.withVersion(num)))
          .getOrElse(Utils.fail(s"Unable to resolve ${dependency.toLine}"))

      // BOM-managed (`*`): recurse with the pinned Numeric for the informational lookup. Unresolved BOM versions are
      // returned as-is (unlike variables they are legal without context, e.g. in group-level update commands).
      case (bom: Dependency.Version.Bom, _) =>
        bom.resolved
          .map(num => findLatestVersion(dependency.withVersion(num)))
          .getOrElse(dependency)

      // Exact-pinned (`=`) numeric: skip the lookup entirely, the user has opted out of updates.
      case (numeric: Dependency.Version.Numeric, _) if numeric.marker.isExact =>
        dependency

      // Numeric with an artifact migration available: query both old and new coordinates, pick the higher version.
      case (numeric: Dependency.Version.Numeric, Some(migration)) =>
        val migrated = dependency
          .withOrganization(migration.groupIdAfter)
          .withName(migration.artifactIdAfter)

        val bestOld = findLatestVersionOf(dependency)(numeric.isValidCandidate)

        val bestNew = findLatestVersionOf(migrated)(numeric.isValidCandidate)

        (bestOld, bestNew) match {
          // Old coordinates have nothing matching the marker; new coordinates do — migrate.
          case (None, Some(nv)) =>
            migrated.withVersion(nv.withMarker(numeric.marker))

          // Both coordinates have a match, but the new one is strictly newer — migrate.
          case (Some(ov), Some(nv)) if Ordering[Numeric].lt(ov, nv) =>
            migrated.withVersion(nv.withMarker(numeric.marker))

          // Old coordinates have a match that's at least as new as the new coordinates — stay put, just bump version.
          case (Some(ov), _) =>
            dependency.withVersion(ov.withMarker(numeric.marker))

          // Neither coordinate resolves — warn and leave the dep unchanged.
          case (None, None) =>
            logger.warn(s"Could not resolve ${dependency.organization}:${dependency.name}")
            dependency
        }

      // Numeric with no migration: plain Maven lookup at current coordinates.
      case (numeric: Dependency.Version.Numeric, None) =>
        findLatestVersionOf(dependency)(numeric.isValidCandidate)
          .map(version => dependency.withVersion(version.withMarker(numeric.marker)))
          .getOrElse {
            logger.warn(s"Could not resolve ${dependency.toLine}")
            dependency
          }
    }
  }

  /** Finds the latest version of a dependency that passes the validation function.
    *
    * @param dependency
    *   The dependency to find the latest version for.
    * @param f
    *   Function to filter valid candidate versions.
    * @return
    *   The latest valid version.
    */
  def findLatestVersionOf(dependency: Dependency)(f: Dependency.Version.Numeric => Boolean)(implicit
      finders: Finders,
      logger: Logger
  ): Option[Dependency.Version.Numeric] =
    findLatestVersion(
      dependency.organization,
      dependency.name,
      dependency.configuration,
      dependency.crossVersion.toSbt
    )(f)

  /** Finds the latest version of a dependency that passes the validation function.
    *
    * @param organization
    *   The organization/groupId.
    * @param name
    *   The artifact name.
    * @param configuration
    *   The dependency configuration (drives the sbt-plugin shape branch).
    * @param crossVersion
    *   The SBT `CrossVersion` shape (drives the cross-versioned shape branch).
    * @param validate
    *   Function to filter valid candidate versions.
    * @return
    *   The latest valid version.
    */
  def findLatestVersion(organization: String, name: String, configuration: String, crossVersion: CrossVersion)(
      validate: Dependency.Version.Numeric => Boolean
  )(implicit finders: Finders, logger: Logger): Option[Dependency.Version.Numeric] = {
    val skipped = ListBuffer.empty[(Dependency.Version.Numeric, TooRecent)]

    val chosen = finders.versionFinder
      .findVersions(organization, name, configuration, crossVersion)
      .filter(validate)
      .sorted
      .reverse
      .iterator
      .find { v =>
        lazy val mavenName =
          VersionFinder.mavenArtifactName(name, finders.scalaVersion.value)(configuration, crossVersion).value

        finders.cooldownFinder
          .cooldownFor(organization, name, v.toVersionString)
          .fold(true) { minimumAge =>
            finders.ageChecker.check(organization, mavenName, v.toVersionString, minimumAge) match {
              case Right(_)        => true
              case Left(tooRecent) =>
                skipped += ((v, tooRecent))
                false
            }
          }
      }

    if (skipped.nonEmpty)
      logger.debug {
        s"Cooldown skipped ${skipped.size} version(s) of $organization:$name: " +
          skipped.map { case (v, t) => s"${v.show} (observed: ${t.observedAge}, required: ${t.requiredAge})" }
            .mkString(", ")
      }

    chosen
  }

  /** Finds the latest Scala version based on the version's marker.
    *
    * The marker controls the update scope:
    *   - `=` (Exact): No updates, returns current version
    *   - `~` (Minor): Updates within same major.minor (e.g., 3.3.x)
    *   - `^` (Major): Updates within same major (e.g., 3.x.y)
    *
    * For Scala 3.8.0+, queries `scala-library`. For earlier Scala 3 versions, queries `scala3-library_3`.
    *
    * @param currentVersion
    *   The current Scala version (may include a marker).
    * @return
    *   The latest version within the allowed range, preserving the original marker.
    */
  def findLatestScalaVersion(
      currentVersion: Numeric
  )(implicit finders: Finders, logger: Logger): Numeric =
    Dependency.scala(currentVersion).findLatestVersion.version match {
      case n: Numeric => n
      case other      => Utils.fail(s"Expected numeric version, got: $other")
    }

  /** Logs an error message and throws a RuntimeException. */
  def fail(message: String)(implicit logger: Logger): Nothing = {
    logger.error(message)
    sys.error(message)
  }

}
