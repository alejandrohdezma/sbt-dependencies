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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.chaining._

import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.model.Dependency
import coursier.cache.FileCache
import coursier.{Dependency => _, _}

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

  private def findVersionsUsingCoursier(module: Module, repositories: Seq[Repository], timeoutSeconds: Int)(implicit
      logger: Logger
  ): List[Dependency.Version.Numeric] = {
    logger.debug(s"Retrieving versions for ${module.organization.value}:${module.name.value}")
    logger.debug(s"Repositories: ${repositories.map(_.repr).mkString(", ")}")

    try {
      val result = Versions()
        .withCache(FileCache().withTtl(None))
        .withModule(module)
        .addRepositories(repositories: _*)
        .versions()
        .future()
        .pipe(Await.result(_, timeoutSeconds.seconds))
        .available
        .collect { case Dependency.Version.Numeric(v) => v }

      logger.debug {
        s"Retrieved ${result.size} versions for `${module.organization.value}:${module.name.value}`:\n" +
          result.map("`" + _.show + "`").mkString(", ")
      }

      result
    } catch {
      case _: TimeoutException =>
        Utils.fail(
          s"Timed out after ${timeoutSeconds}s resolving versions for " +
            s"${module.organization.value}:${module.name.value}. " +
            "Try increasing `dependencyResolverTimeout`."
        )
    }
  }

  /** Creates a VersionFinder that uses Coursier to resolve versions.
    *
    * @param scalaBinaryVersion
    *   The Scala binary version for cross-compiled dependencies.
    * @param timeout
    *   Timeout in seconds for Coursier version resolution requests.
    * @param repositories
    *   Additional Coursier repositories to query for versions, on top of Coursier defaults (Maven Central + Ivy2
    *   local).
    */
  def fromCoursier(scalaBinaryVersion: String, timeout: Int = 60, repositories: Seq[Repository])(implicit
      logger: Logger
  ): VersionFinder = {
    case (organization, name, true, _) =>
      findVersionsUsingCoursier(
        Module(Organization(organization), ModuleName(s"${name}_$scalaBinaryVersion")),
        repositories,
        timeout
      )
    case (organization, name, _, true) =>
      val binaryModule =
        Module(Organization(organization), ModuleName(s"${name}_2.12_1.0"))

      val moduleWithAttributes =
        Module(Organization(organization), ModuleName(name), Map("scalaVersion" -> "2.12", "sbtVersion" -> "1.0"))

      findVersionsUsingCoursier(binaryModule, repositories, timeout) ++
        findVersionsUsingCoursier(moduleWithAttributes, repositories, timeout)
    case (organization, name, _, _) =>
      findVersionsUsingCoursier(Module(Organization(organization), ModuleName(name)), repositories, timeout)
  }

  implicit class VersionFinderOps(private val underlying: VersionFinder) extends AnyVal {

    /** Wraps this `VersionFinder` with a `ConcurrentHashMap`-backed cache so each unique coordinate tuple is resolved
      * at most once.
      */
    def cached: VersionFinder = {
      val cache = new ConcurrentHashMap[(String, String, Boolean, Boolean), List[Dependency.Version.Numeric]]()

      (organization, name, isCross, isSbtPlugin) =>
        cache.computeIfAbsent(
          (organization, name, isCross, isSbtPlugin),
          tuple => (underlying.findVersions _).tupled(tuple)
        )
    }

    /** Wraps this `VersionFinder` to filter out versions matched by the given `IgnoreFinder`. */
    def ignoringVersions(ignoreFinder: IgnoreFinder)(implicit logger: Logger): VersionFinder =
      (organization, name, isCross, isSbtPlugin) => {
        val versions = underlying
          .findVersions(organization, name, isCross, isSbtPlugin)

        val filtered = versions.filterNot(v => ignoreFinder.isIgnored(organization, name, v.toVersionString))

        logger.debug {
          s"Filtered ${filtered.size} versions for `$organization:$name` after ignoring:\n" +
            filtered.map("`" + _.show + "`").mkString(", ")
        }

        filtered
      }

    /** Wraps this `VersionFinder` to filter out versions matched by the given `RetractionFinder`. */
    def excludingRetracted(retractionFinder: RetractionFinder)(implicit logger: Logger): VersionFinder =
      (organization, name, isCross, isSbtPlugin) => {
        val versions = underlying
          .findVersions(organization, name, isCross, isSbtPlugin)

        val filtered = versions.filterNot(v => retractionFinder.isRetracted(organization, name, v.toVersionString))

        logger.debug {
          s"Filtered ${filtered.size} versions for `$organization:$name` after excluding retracted:\n" +
            filtered.map("`" + _.show + "`").mkString(", ")
        }

        filtered
      }

    /** Wraps this `VersionFinder` to keep only versions allowed by the given `PinFinder`. */
    def pinningVersions(pinFinder: PinFinder)(implicit logger: Logger): VersionFinder =
      (organization, name, isCross, isSbtPlugin) => {
        val versions = underlying
          .findVersions(organization, name, isCross, isSbtPlugin)

        val filtered = versions.filter(v => pinFinder.isAllowed(organization, name, v.toVersionString))

        logger.debug {
          s"Filtered ${filtered.size} versions for `$organization:$name` after pinning:\n" +
            filtered.map("`" + _.show + "`").mkString(", ")
        }

        filtered
      }

  }

}
