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

package com.alejandrohdezma.sbt.dependencies.constraints

import java.net.URL
import java.util.concurrent.ConcurrentHashMap

import scala.Console._
import scala.jdk.CollectionConverters._
import scala.util.Try

import sbt.url
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.ConfigOps
import com.alejandrohdezma.sbt.dependencies.TryOps
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValueType

/** Represents an entry from the `updates.ignore` section of a Scala Steward configuration file.
  *
  * @param groupId
  *   The organization/groupId to match (required).
  * @param artifactId
  *   The artifact name to match (optional; if absent, matches all artifacts in groupId).
  * @param version
  *   The version pattern to match (optional; if absent, matches all versions).
  */
final case class UpdateIgnore(
    groupId: String,
    artifactId: Option[String] = None,
    version: Option[VersionPattern] = None
) {

  /** Checks if this ignore entry matches the given dependency coordinates. */
  def matches(organization: String, name: String, versionString: String): Boolean = {
    val groupMatches    = groupId === organization
    val artifactMatches = artifactId.forall(_ === name)
    val versionMatches  = version.forall(_.matches(versionString))

    groupMatches && artifactMatches && versionMatches
  }

}

object UpdateIgnore {

  /** Per-URL cache so each URL is fetched and parsed only once per JVM session. */
  private val cache = new ConcurrentHashMap[URL, List[UpdateIgnore]]()

  /** The default list of update-ignore URLs. */
  val default = List(
    url("https://raw.githubusercontent.com/scala-steward-org/scala-steward/main/modules/core/src/main/resources/default.scala-steward.conf")
  )

  /** Loads update-ignore entries from a list of URLs.
    *
    * Supports both `https://` and `file://` URLs. Each URL is fetched and parsed as HOCON. Files without an
    * `updates.ignore` key are silently skipped.
    *
    * @param urls
    *   The list of URLs to load ignore entries from.
    * @return
    *   Combined list of all ignore entries from all URLs.
    */
  @SuppressWarnings(Array("scalafix:DisableSyntax.throw"))
  def loadFromUrls(urls: List[URL])(implicit logger: Logger): List[UpdateIgnore] = urls.flatMap { url =>
    Option(cache.get(url)).getOrElse {
      logger.info(s"↻ Loading update ignores from $CYAN$url$RESET")

      val ignores = Try {
        val config = ConfigFactory.parseURL(url)

        if (!config.hasPath("updates.ignore")) Nil
        else
          config.getConfigList("updates.ignore").asScala.toList.zipWithIndex.flatMap { case (entry, index) =>
            Try {
              if (!entry.hasPath("groupId"))
                throw new IllegalArgumentException(s"entry at index $index must have a 'groupId'")

              val groupId    = entry.get("groupId").get
              val artifactId = entry.get("artifactId")

              val version =
                if (!entry.hasPath("version")) None
                else
                  entry.getValue("version").valueType() match {
                    case ConfigValueType.STRING =>
                      Some(VersionPattern(prefix = entry.get("version")))

                    case ConfigValueType.OBJECT =>
                      val obj = entry.getValue("version").asInstanceOf[ConfigObject].toConfig

                      Some(VersionPattern(obj.get("prefix"), obj.get("suffix"), obj.get("exact"), obj.get("contains")))

                    case other =>
                      throw new IllegalArgumentException(s"entry at index $index has unsupported version type: $other")
                  }

              List(UpdateIgnore(groupId, artifactId, version))
            }.onError { case e => logger.warn(s"⚠ Skipping malformed update-ignore entry from $CYAN$url$RESET: $e") }
              .getOrElse(Nil)
          }
      }.onError { case e => logger.warn(s"⚠ Failed to load update ignores from $CYAN$url$RESET: $e") }
        .getOrElse(Nil)

      cache.put(url, ignores)

      ignores
    }
  }

}
