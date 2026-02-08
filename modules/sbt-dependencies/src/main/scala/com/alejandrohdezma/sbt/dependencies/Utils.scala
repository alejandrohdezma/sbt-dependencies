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

package com.alejandrohdezma.sbt.dependencies

import scala.concurrent.ExecutionContext.Implicits.global

import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.Eq._
import coursier.cache.FileCache
import coursier.{Dependency => _, _}

/** Utility functions for dependency resolution. */
object Utils {

  /** Abstraction for finding available versions of a dependency. */
  trait VersionFinder {

    /** Finds all available versions for the given dependency coordinates.
      *
      * @param organization
      *   The organization/groupId.
      * @param name
      *   The artifact name.
      * @param isCross
      *   Whether the dependency is cross-compiled for Scala.
      * @param isSbtPlugin
      *   Whether the dependency is an SBT plugin.
      * @return
      *   List of available versions.
      */
    def findVersions(
        organization: String,
        name: String,
        isCross: Boolean,
        isSbtPlugin: Boolean
    ): List[Dependency.Version.Numeric]

  }

  object VersionFinder {

    private def findVersionsUsingCoursier(module: Module): List[Dependency.Version.Numeric] =
      Versions()
        .withCache(FileCache().noCredentials.withTtl(None))
        .withModule(module)
        .versions()
        .unsafeRun()
        .available
        .collect { case Dependency.Version.Numeric(v) => v }

    /** Creates a VersionFinder that uses Coursier to resolve versions. */
    def fromCoursier(scalaBinaryVersion: String): VersionFinder = {
      case (organization, name, true, _) =>
        findVersionsUsingCoursier(Module(Organization(organization), ModuleName(s"${name}_$scalaBinaryVersion")))
      case (organization, name, _, true) =>
        val binaryModule =
          Module(Organization(organization), ModuleName(s"${name}_2.12_1.0"))

        val moduleWithAttributes =
          Module(Organization(organization), ModuleName(name), Map("scalaVersion" -> "2.12", "sbtVersion" -> "1.0"))

        findVersionsUsingCoursier(binaryModule) ++ findVersionsUsingCoursier(moduleWithAttributes)
      case (organization, name, _, _) =>
        findVersionsUsingCoursier(Module(Organization(organization), ModuleName(name)))
    }

  }

  /** Finds the latest version of a dependency based on the current version's marker.
    *
    * The marker controls the update scope:
    *   - `=` (Exact): No updates, returns current version
    *   - `~` (Minor): Updates within same major.minor
    *   - `^` (Major): Updates within same major
    *
    * For variable versions, delegates to the resolved numeric version.
    *
    * @param organization
    *   The organization/groupId.
    * @param name
    *   The artifact name.
    * @param isCross
    *   Whether the dependency is cross-compiled for Scala.
    * @param current
    *   The current version (may be numeric or variable).
    * @param configuration
    *   The dependency configuration (e.g., "compile", "test", "sbt-plugin").
    * @return
    *   A [[Dependency.WithNumericVersion]] with the latest resolved version, preserving the original marker.
    */
  def findLatestVersion(
      organization: String,
      name: String,
      isCross: Boolean,
      current: Dependency.Version,
      configuration: String
  )(implicit versionFinder: VersionFinder, logger: Logger): Dependency.WithNumericVersion = {
    val isSbtPlugin = configuration === "sbt-plugin"

    current match {
      case variable: Dependency.Version.Variable =>
        findLatestVersion(organization, name, isCross, variable.resolved, configuration)

      case numeric: Dependency.Version.Numeric =>
        if (numeric.marker === Dependency.Version.Numeric.Marker.Exact) {
          Dependency.WithNumericVersion(organization, name, numeric, isCross, configuration)
        } else {
          val latest = Utils
            .findLatestVersion(organization, name, isCross, isSbtPlugin)(numeric.isValidCandidate)
            .copy(marker = numeric.marker)

          Dependency.WithNumericVersion(organization, name, latest, isCross, configuration)
        }
    }

  }

  /** Finds the latest version of a dependency that passes the validation function.
    *
    * @param organization
    *   The organization/groupId.
    * @param name
    *   The artifact name.
    * @param isCross
    *   Whether the dependency is cross-compiled for Scala.
    * @param isSbtPlugin
    *   Whether the dependency is an SBT plugin.
    * @param validate
    *   Function to filter valid candidate versions.
    * @return
    *   The latest valid version.
    */
  def findLatestVersion(organization: String, name: String, isCross: Boolean, isSbtPlugin: Boolean)(
      validate: Dependency.Version.Numeric => Boolean
  )(implicit versionFinder: VersionFinder, logger: Logger): Dependency.Version.Numeric =
    versionFinder
      .findVersions(organization, name, isCross, isSbtPlugin)
      .filter(validate)
      .sorted
      .reverse
      .headOption
      .getOrElse(fail(s"Could not resolve $organization:$name"))

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
  def findLatestScalaVersion(currentVersion: Numeric)(implicit
      versionFinder: VersionFinder,
      logger: Logger
  ): Numeric = {
    val name =
      if (currentVersion.major === 3 && currentVersion.minor < 8) "scala3-library_3"
      else "scala-library"

    findLatestVersion("org.scala-lang", name, isCross = false, currentVersion, "compile").version
  }

  /** Logs an error message and throws a RuntimeException. */
  def fail(message: String)(implicit logger: Logger): Nothing = {
    logger.error(message)
    sys.error(message)
  }

}
