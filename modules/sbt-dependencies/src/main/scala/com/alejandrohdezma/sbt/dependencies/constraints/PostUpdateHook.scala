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
import com.typesafe.config.Config

/** Represents an entry from the `postUpdateHooks` section of a Scala Steward configuration file.
  *
  * @param groupId
  *   The organization/groupId to match (optional; if absent, matches all groupIds).
  * @param artifactId
  *   The artifact name to match (optional; if absent, matches all artifacts in groupId).
  * @param command
  *   The command to run as a list of strings (e.g., `["sbt", "scalafixAll"]`).
  * @param commitMessage
  *   The commit message template. Supports `\${nextVersion}`, `\${currentVersion}`, and `\${artifactName}` variables.
  */
final case class PostUpdateHook(
    groupId: Option[String],
    artifactId: Option[String],
    command: List[String],
    commitMessage: String
)

object PostUpdateHook extends Cached[PostUpdateHook] {

  implicit val PostUpdateHookConfigDecoder: ConfigDecoder[List[PostUpdateHook]] =
    ConfigDecoder.optionalConfigList[PostUpdateHook] { config =>
      for {
        groupId       <- config.as[Option[String]]("groupId")
        artifactId    <- config.as[Option[String]]("artifactId")
        command       <- config.asNonEmptyList("command")
        commitMessage <- config.as[String]("commitMessage")
      } yield PostUpdateHook(groupId, artifactId, command, commitMessage)
    }

  /** The default list of post-update hook URLs. */
  val default = List(
    url("https://raw.githubusercontent.com/scala-steward-org/scala-steward/main/modules/core/src/main/resources/default.scala-steward.conf")
  )

  def configToValue(config: Config): Either[String, List[PostUpdateHook]] =
    config.as[List[PostUpdateHook]]("postUpdateHooks")

}
