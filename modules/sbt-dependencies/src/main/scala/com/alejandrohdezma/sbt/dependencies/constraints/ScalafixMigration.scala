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

/** Represents an entry from the `migrations` section of a Scala Steward scalafix migrations configuration file.
  *
  * @param groupId
  *   The organization/groupId to match (required).
  * @param artifactIds
  *   Regex patterns for artifact names to match (required).
  * @param newVersion
  *   The version at which this migration applies. Migration triggers when `currentVersion < newVersion <= nextVersion`.
  * @param rewriteRules
  *   The scalafix rewrite rules to apply.
  * @param doc
  *   Optional URL to migration documentation.
  * @param scalacOptions
  *   Extra scalac options needed when running the migration (e.g., `"-P:semanticdb:synthetics:on"`).
  */
final case class ScalafixMigration(
    groupId: String,
    artifactIds: List[String],
    newVersion: String,
    rewriteRules: List[String],
    doc: Option[String] = None,
    scalacOptions: List[String] = Nil
)

object ScalafixMigration extends Cached[ScalafixMigration] {

  implicit val ScalafixMigrationConfigDecoder: ConfigDecoder[List[ScalafixMigration]] =
    ConfigDecoder.optionalConfigList[ScalafixMigration] { config =>
      for {
        groupId       <- config.as[String]("groupId")
        artifactIds   <- config.asNonEmptyList("artifactIds")
        newVersion    <- config.as[String]("newVersion")
        rewriteRules  <- config.asNonEmptyList("rewriteRules")
        doc           <- config.as[Option[String]]("doc")
        scalacOptions <- config.as[List[String]]("scalacOptions")
      } yield ScalafixMigration(groupId, artifactIds, newVersion, rewriteRules, doc, scalacOptions)
    }

  /** The default list of scalafix migration URLs. */
  val default = List(
    url("https://raw.githubusercontent.com/scala-steward-org/scala-steward/main/modules/core/src/main/resources/scalafix-migrations.conf")
  )

  def configToValue(config: Config): Either[String, List[ScalafixMigration]] =
    config.as[List[ScalafixMigration]]("migrations")

}
