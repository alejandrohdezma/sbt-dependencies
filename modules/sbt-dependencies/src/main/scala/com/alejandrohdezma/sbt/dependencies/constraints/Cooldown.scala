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

import scala.concurrent.duration.FiniteDuration

import com.alejandrohdezma.sbt.dependencies.config._
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.typesafe.config.Config

/** Represents a cooldown rule loaded from a Scala-Steward-style HOCON configuration file.
  *
  * Cooldown filters out candidate versions that were published less than `minimumAge` ago. A `CooldownDefault` applies
  * to every artifact unless an earlier-matching `CooldownOverride` takes precedence — first matching override wins,
  * default applies otherwise.
  *
  * Schema mirrors Scala Steward (see https://github.com/scala-steward-org/scala-steward/pull/3881):
  *
  * {{{
  *   updates.cooldown = { minimumAge = "7 days" }
  *
  *   dependencyOverrides = [
  *     {
  *       dependency = { groupId = "com.my-company" },
  *       cooldown   = { minimumAge = "0 seconds" }
  *     }
  *     {
  *       dependency = { groupId = "org.example", artifactId = "lib", version = { prefix = "1." } },
  *       cooldown   = { minimumAge = "30 days" }
  *     }
  *   ]
  * }}}
  *
  * Entries in `dependencyOverrides` that don't carry a `cooldown` block are ignored by this decoder — Scala Steward's
  * `dependencyOverrides` can also override `pullRequests`, which this plugin doesn't surface.
  */
sealed trait CooldownEntry

/** The build-wide default cooldown, applied to every artifact that no override matches. */
final case class CooldownDefault(minimumAge: FiniteDuration) extends CooldownEntry

/** A per-pattern override. `matches` follows the same shape as `UpdatePin` / `UpdateIgnore`: `groupId` must match
  * exactly, `artifactId` and `version` are optional refinements.
  */
final case class CooldownOverride(
    groupId: String,
    artifactId: Option[String],
    version: Option[VersionPattern],
    minimumAge: FiniteDuration
) extends CooldownEntry {

  def matches(organization: String, name: String, versionString: String): Boolean = {
    val groupMatches    = groupId === organization
    val artifactMatches = artifactId.forall(_ === name)
    val versionMatches  = version.forall(_.matches(versionString))

    groupMatches && artifactMatches && versionMatches
  }

}

object CooldownEntry extends Cached[CooldownEntry] {

  /** Default URL list: empty. Cooldown is per-repo user policy, not an ecosystem-wide fact, so we don't point at any
    * Scala-Steward URL by default. Users opt in via `dependencyCooldowns += file("...").toURI.toURL`.
    */
  val default: List[java.net.URL] = Nil

  def configToValue(config: Config): Either[String, List[CooldownEntry]] =
    for {
      defaultEntry <- decodeDefault(config)
      overrides    <- decodeOverrides(config)
    } yield defaultEntry.toList ++ overrides

  private def decodeDefault(config: Config): Either[String, Option[CooldownDefault]] =
    config.as[Option[FiniteDuration]]("updates.cooldown.minimumAge").map(_.map(CooldownDefault(_)))

  private def decodeOverrides(config: Config): Either[String, List[CooldownOverride]] =
    ConfigDecoder
      .optionalConfigList[Option[CooldownOverride]] { entry =>
        if (!entry.hasPath("cooldown.minimumAge")) Right(None)
        else
          for {
            groupId    <- entry.as[String]("dependency.groupId")
            artifactId <- entry.as[Option[String]]("dependency.artifactId")
            version    <- decodeOverrideVersion(entry)
            minimumAge <- entry.as[FiniteDuration]("cooldown.minimumAge")
          } yield Some(CooldownOverride(groupId, artifactId, version, minimumAge))
      }
      .decode(config, "dependencyOverrides")
      .map(_.flatten)

  /** `VersionPattern.configDecoder` reads from a path on a parent `Config`; here the parent is the override entry and
    * the path is `dependency.version`, so we hop through `getConfig("dependency")` and then ask for `"version"`.
    * Mirrors what `RetractedArtifact` does for its nested `artifacts[]` decoder.
    */
  private def decodeOverrideVersion(entry: Config): Either[String, Option[VersionPattern]] =
    if (!entry.hasPath("dependency")) Right(None)
    else entry.getConfig("dependency").as[Option[VersionPattern]]("version")

}
