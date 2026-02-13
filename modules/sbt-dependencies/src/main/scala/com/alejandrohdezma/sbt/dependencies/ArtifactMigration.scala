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

import java.net.URL
import java.util.concurrent.ConcurrentHashMap

import scala.Console._
import scala.jdk.CollectionConverters._
import scala.util.Try

import sbt.url
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Eq._
import com.typesafe.config.ConfigFactory

/** Represents an artifact migration from old coordinates to new coordinates.
  *
  * Supports three migration shapes:
  *   - Group-only change: only groupId changes
  *   - Artifact-only change: only artifactId changes
  *   - Both change: both groupId and artifactId change
  *
  * At least one of `groupIdBefore` or `artifactIdBefore` must be defined.
  *
  * @param groupIdBefore
  *   The old groupId (None if only artifact changes)
  * @param groupIdAfter
  *   The new groupId
  * @param artifactIdBefore
  *   The old artifactId (None if only group changes)
  * @param artifactIdAfter
  *   The new artifactId
  */
final case class ArtifactMigration(
    groupIdBefore: Option[String],
    groupIdAfter: String,
    artifactIdBefore: Option[String],
    artifactIdAfter: String
) {

  /** Checks if this migration matches the given dependency.
    *
    * The group matches if `groupIdBefore` equals the dependency's organization, or if `groupIdBefore` is not defined
    * and `groupIdAfter` equals the dependency's organization.
    *
    * The artifact matches if `artifactIdBefore` equals the dependency's name, or if `artifactIdBefore` is not defined
    * and `artifactIdAfter` equals the dependency's name.
    */
  def matches(dep: Dependency): Boolean = {
    val groupMatches    = groupIdBefore.getOrElse(groupIdAfter) === dep.organization
    val artifactMatches = artifactIdBefore.getOrElse(artifactIdAfter) === dep.name
    groupMatches && artifactMatches
  }

}

object ArtifactMigration {

  private val cache = new ConcurrentHashMap[URL, List[ArtifactMigration]]()

  /** The default list of artifact migrations. */
  val default = List(
    url("https://raw.githubusercontent.com/scala-steward-org/scala-steward/main/modules/core/src/main/resources/artifact-migrations.v2.conf")
  )

  /** Loads artifact migrations from a list of URLs.
    *
    * Supports both `https://` and `file://` URLs. Each URL is fetched and parsed as HOCON.
    *
    * @param urls
    *   The list of URLs to load migrations from
    * @return
    *   Combined list of all migrations from all URLs
    */
  def loadFromUrls(urls: List[URL])(implicit logger: Logger): List[ArtifactMigration] = urls.flatMap { url =>
    Option(cache.get(url)).getOrElse {
      logger.info(s"↻ Loading migrations from $CYAN$url$RESET")

      val config = Try(ConfigFactory.parseURL(url)).recover { case e =>
        Utils.fail(s"Failed to parse migration file $url: ${e.getMessage}")
      }.get

      if (!config.hasPath("changes"))
        Utils.fail("Migration file must contain a 'changes' array")

      val migrations = config.getConfigList("changes").asScala.toList.zipWithIndex.map { case (change, index) =>
        if (!change.hasPath("groupIdBefore") && !change.hasPath("artifactIdBefore"))
          Utils.fail(s"Migration entry at index $index must have at least one of 'groupIdBefore' or 'artifactIdBefore'")

        if (!change.hasPath("groupIdAfter"))
          Utils.fail(s"Migration entry at index $index must have a 'groupIdAfter'")

        if (!change.hasPath("artifactIdAfter"))
          Utils.fail(s"Migration entry at index $index must have a 'artifactIdAfter'")

        ArtifactMigration(
          groupIdBefore = change.get("groupIdBefore"),
          groupIdAfter = change.get("groupIdAfter").get,
          artifactIdBefore = change.get("artifactIdBefore"),
          artifactIdAfter = change.get("artifactIdAfter").get
        )
      }

      cache.put(url, migrations)
      migrations
    }
  }

}
