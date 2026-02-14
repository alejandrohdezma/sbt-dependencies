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
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValueType

/** Represents an entry from the `updates.retracted` section of a Scala Steward configuration file.
  *
  * Each entry is a flattened artifact from the nested Scala Steward format, carrying its parent's `reason` and `doc`.
  *
  * @param reason
  *   Why this version was retracted.
  * @param doc
  *   URL to documentation about the retraction.
  * @param groupId
  *   The organization/groupId to match (required).
  * @param artifactId
  *   The artifact name to match (optional; if absent, matches all artifacts in groupId).
  * @param version
  *   The version pattern to match (optional; if absent, matches all versions).
  */
final case class RetractedArtifact(
    reason: String,
    doc: String,
    groupId: String,
    artifactId: Option[String] = None,
    version: Option[VersionPattern] = None
) {

  /** Checks if this retraction entry matches the given dependency coordinates. */
  def matches(organization: String, name: String, versionString: String): Boolean = {
    val groupMatches    = groupId === organization
    val artifactMatches = artifactId.forall(_ === name)
    val versionMatches  = version.forall(_.matches(versionString))

    groupMatches && artifactMatches && versionMatches
  }

}

object RetractedArtifact {

  /** Per-URL cache so each URL is fetched and parsed only once per JVM session. */
  private val cache = new ConcurrentHashMap[URL, List[RetractedArtifact]]()

  /** The default list of retraction URLs. */
  val default = List(
    url("https://raw.githubusercontent.com/scala-steward-org/scala-steward/main/modules/core/src/main/resources/default.scala-steward.conf")
  )

  /** Loads retracted-artifact entries from a list of URLs.
    *
    * Supports both `https://` and `file://` URLs. Each URL is fetched and parsed as HOCON. Files without an
    * `updates.retracted` key are silently skipped.
    *
    * The Scala Steward format nests artifacts inside each retracted entry. This method flattens them so each
    * `RetractedArtifact` is self-contained with the parent's `reason` and `doc`.
    *
    * @param urls
    *   The list of URLs to load retraction entries from.
    * @return
    *   Combined list of all retraction entries from all URLs.
    */
  @SuppressWarnings(Array("scalafix:DisableSyntax.throw"))
  def loadFromUrls(urls: List[URL])(implicit logger: Logger): List[RetractedArtifact] = urls.flatMap { url =>
    Option(cache.get(url)).getOrElse {
      logger.info(s"↻ Loading retracted versions from $CYAN$url$RESET")

      val retractions = Try {
        val config = ConfigFactory.parseURL(url)

        if (!config.hasPath("updates.retracted")) Nil
        else
          config.getConfigList("updates.retracted").asScala.toList.zipWithIndex.flatMap { case (entry, index) =>
            Try {
              val reason = entry.get("reason").getOrElse {
                throw new IllegalArgumentException(s"entry at index $index must have a 'reason'")
              }

              val doc = entry.get("doc").getOrElse {
                throw new IllegalArgumentException(s"entry at index $index must have a 'doc'")
              }

              if (!entry.hasPath("artifacts"))
                throw new IllegalArgumentException(s"entry at index $index must have an 'artifacts' list")

              entry.getConfigList("artifacts").asScala.toList.zipWithIndex.flatMap { case (artifact, artIndex) =>
                Try {
                  if (!artifact.hasPath("groupId"))
                    throw new IllegalArgumentException(
                      s"artifact at index $artIndex in entry $index must have a 'groupId'"
                    )

                  val groupId    = artifact.get("groupId").get
                  val artifactId = artifact.get("artifactId")

                  val version =
                    if (!artifact.hasPath("version")) None
                    else
                      artifact.getValue("version").valueType() match {
                        case ConfigValueType.STRING =>
                          Some(VersionPattern(prefix = artifact.get("version")))

                        case ConfigValueType.OBJECT =>
                          val obj = artifact.getValue("version").asInstanceOf[ConfigObject].toConfig

                          Some(
                            VersionPattern(obj.get("prefix"), obj.get("suffix"), obj.get("exact"), obj.get("contains"))
                          )

                        case other =>
                          throw new IllegalArgumentException(
                            s"artifact at index $artIndex in entry $index has unsupported version type: $other"
                          )
                      }

                  List(RetractedArtifact(reason, doc, groupId, artifactId, version))
                }.onError { case e =>
                  logger.warn(s"⚠ Skipping malformed retracted-artifact entry from $CYAN$url$RESET: $e")
                }.getOrElse(Nil)
              }
            }.onError { case e =>
              logger.warn(s"⚠ Skipping malformed retracted entry from $CYAN$url$RESET: $e")
            }.getOrElse(Nil)
          }
      }.onError { case e => logger.warn(s"⚠ Failed to load retracted versions from $CYAN$url$RESET: $e") }
        .getOrElse(Nil)

      cache.put(url, retractions)

      retractions
    }
  }

}
