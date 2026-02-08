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

import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.Eq._

/** Utility functions for dependency resolution. */
object Utils {

  /** Finds the latest version of a dependency based on the current version's marker.
    *
    * The marker controls the update scope:
    *   - `=` (Exact): No updates, returns current version
    *   - `~` (Minor): Updates within same major.minor
    *   - `^` (Major): Updates within same major
    *
    * For variable versions, delegates to the resolved numeric version.
    *
    * @param dependency
    *   The dependency to find the latest version for.
    * @return
    *   A [[Dependency.WithNumericVersion]] with the latest resolved version, preserving the original marker.
    */
  def findLatestVersion(
      dependency: Dependency
  )(implicit versionFinder: VersionFinder, logger: Logger): Dependency.WithNumericVersion = {
    val isSbtPlugin = dependency.configuration === "sbt-plugin"

    dependency.version match {
      case variable: Dependency.Version.Variable =>
        findLatestVersion(dependency.withVersion(variable.resolved))

      case numeric: Dependency.Version.Numeric =>
        if (numeric.marker === Dependency.Version.Numeric.Marker.Exact) {
          Dependency.WithNumericVersion(dependency.organization, dependency.name, numeric, dependency.isCross,
            dependency.configuration)
        } else {
          val latest = Utils
            .findLatestVersion(dependency.organization, dependency.name, dependency.isCross, isSbtPlugin) {
              numeric.isValidCandidate
            }
            .copy(marker = numeric.marker)

          Dependency.WithNumericVersion(dependency.organization, dependency.name, latest, dependency.isCross,
            dependency.configuration)
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
  def findLatestScalaVersion(currentVersion: Numeric)(implicit versionFinder: VersionFinder, logger: Logger): Numeric =
    Dependency.scala(currentVersion).findLatestVersion.version

  /** Logs an error message and throws a RuntimeException. */
  def fail(message: String)(implicit logger: Logger): Nothing = {
    logger.error(message)
    sys.error(message)
  }

}
