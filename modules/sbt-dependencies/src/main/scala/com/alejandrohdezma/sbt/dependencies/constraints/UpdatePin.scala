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

import sbt.url

import com.alejandrohdezma.sbt.dependencies.config._
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.typesafe.config.Config

/** Represents an entry from the `updates.pin` section of a Scala Steward configuration file.
  *
  * @param groupId
  *   The organization/groupId to match (required).
  * @param artifactId
  *   The artifact name to match (optional; if absent, matches all artifacts in groupId).
  * @param version
  *   The version pattern to match (optional; if absent, allows all versions).
  */
final case class UpdatePin(
    groupId: String,
    artifactId: Option[String] = None,
    version: Option[VersionPattern] = None
) {

  /** Checks if this pin entry matches the given artifact coordinates (groupId and optionally artifactId). */
  def matchesArtifact(organization: String, name: String): Boolean = {
    val groupMatches    = groupId === organization
    val artifactMatches = artifactId.forall(_ === name)

    groupMatches && artifactMatches
  }

  /** Checks if the given version string matches this pin's version pattern. Returns true when no version pattern is
    * defined.
    */
  def matchesVersion(versionString: String): Boolean =
    version.forall(_.matches(versionString))

}

object UpdatePin extends Cached[UpdatePin] {

  implicit val UpdatePinConfigDecoder: ConfigDecoder[List[UpdatePin]] =
    ConfigDecoder.optionalConfigList[UpdatePin] { config =>
      for {
        groupId    <- config.as[String]("groupId")
        artifactId <- config.as[Option[String]]("artifactId")
        version    <- config.as[Option[VersionPattern]]("version")
      } yield UpdatePin(groupId, artifactId, version)
    }

  /** The default list of update-pin URLs. */
  val default = List(
    url("https://raw.githubusercontent.com/scala-steward-org/scala-steward/main/modules/core/src/main/resources/default.scala-steward.conf")
  )

  def configToValue(config: Config): Either[String, List[UpdatePin]] =
    config.as[List[UpdatePin]]("updates.pin")

}
