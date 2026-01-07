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

  /** Finds the latest version of a dependency that passes the validation function.
    *
    * @param dependency
    *   The dependency to find the latest version of.
    * @param validate
    *   Function to filter valid candidate versions.
    * @return
    *   The latest valid version.
    */
  def findLatestVersion(dependency: Dependency)(
      validate: Dependency.Version.Numeric => Boolean
  )(implicit versionFinder: VersionFinder, logger: Logger): Dependency.Version.Numeric =
    findLatestVersion(
      dependency.organization,
      dependency.name,
      dependency.isCross,
      dependency.configuration === "sbt-plugin"
    )(validate)

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

  /** Logs an error message and throws a RuntimeException. */
  def fail(message: String)(implicit logger: Logger): Nothing = {
    logger.error(message)
    sys.error(message)
  }

}
