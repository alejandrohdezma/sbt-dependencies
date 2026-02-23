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
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.typesafe.config.Config

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

object ArtifactMigration extends Cached[ArtifactMigration] {

  implicit val ArtifactMigrationConfigDecoder: ConfigDecoder[List[ArtifactMigration]] =
    ConfigDecoder.configList[ArtifactMigration] { config =>
      for {
        _ <- if (config.hasPath("groupIdBefore") || config.hasPath("artifactIdBefore")) Right(())
             else Left("must have at least one of 'groupIdBefore' or 'artifactIdBefore'")
        groupIdBefore    <- config.as[Option[String]]("groupIdBefore")
        groupIdAfter     <- config.as[String]("groupIdAfter")
        artifactIdBefore <- config.as[Option[String]]("artifactIdBefore")
        artifactIdAfter  <- config.as[String]("artifactIdAfter")
      } yield ArtifactMigration(groupIdBefore, groupIdAfter, artifactIdBefore, artifactIdAfter)
    }

  /** The default list of artifact migrations. */
  val default = List(
    url("https://raw.githubusercontent.com/scala-steward-org/scala-steward/main/modules/core/src/main/resources/artifact-migrations.v2.conf")
  )

  def configToValue(config: Config): Either[String, List[ArtifactMigration]] =
    config.as[List[ArtifactMigration]]("changes")

}
