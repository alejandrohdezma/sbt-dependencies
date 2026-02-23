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

package com.alejandrohdezma.sbt.dependencies.io

import com.alejandrohdezma.sbt.dependencies.constraints.PostUpdateHook
import com.alejandrohdezma.sbt.dependencies.constraints.ScalafixMigration
import com.alejandrohdezma.sbt.dependencies.io.DependencyDiff.ProjectDiff
import com.alejandrohdezma.sbt.dependencies.model.Eq._

/** A script to be run after updating dependencies, with a human-readable message.
  *
  * @param script
  *   The bash command to execute.
  * @param message
  *   A human-readable description, suitable for use as a commit message.
  */
final case class UpdateScript(script: String, message: String)

object UpdateScript {

  /** Matches post-update hooks against the dependency diff and generates scripts.
    *
    * For each hook, finds updated dependencies matching its groupId/artifactId filter. Generates one script per
    * matching (hook, dep) pair with variable substitution in the commit message.
    */
  def fromHooks(hooks: List[PostUpdateHook], diffs: Map[String, ProjectDiff]): List[UpdateScript] = {
    val allUpdated = diffs.values.flatMap(_.updated).toList

    hooks.flatMap { hook =>
      val matches = allUpdated.filter { dep =>
        hook.groupId.forall(_ === dep.organization) && hook.artifactId.forall(_ === dep.name)
      }

      matches.map { dep =>
        val script = hook.command.mkString(" ")

        val message = hook.commitMessage
          .replace("${nextVersion}", dep.to)
          .replace("${currentVersion}", dep.from)
          .replace("${artifactName}", dep.name)

        UpdateScript(script, message)
      }
    }
  }.distinctBy(_.script)

  /** Matches scalafix migrations against the dependency diff and generates project-scoped scripts.
    *
    * Iterates over each project in the diff. For each project's updated dependencies, checks if any migration matches
    * using Scala Steward's matching logic: groupId exact match, artifactIds regex match, and version range check (`from
    * < newVersion && to >= newVersion`).
    *
    * The execution mode is derived from the project where the dependency was updated:
    *   - `sbt-build` (plugins, sbt itself) → runs `scalafix` CLI directly on build files
    *   - any other project → runs `sbt "scalafixEnable; project/scalafixAll rule"` on source files
    */
  def fromMigrations(migrations: List[ScalafixMigration], diffs: Map[String, ProjectDiff]): List[UpdateScript] =
    diffs.toList.flatMap { case (project, diff) =>
      migrations.flatMap {
        case migration if diff.updated.exists(migration.matches(_)) => List(migration.toScript(project))
        case _                                                      => Nil
      }
    }.distinctBy(_.script)

  /** Renders a list of update scripts as a JSON array string. */
  def toJson(scripts: List[UpdateScript]): String =
    if (scripts.isEmpty) "[]"
    else {
      val entries = scripts.map { script =>
        val escapedScript  = escapeJson(script.script)
        val escapedMessage = escapeJson(script.message)

        s"""  {"script": "$escapedScript", "message": "$escapedMessage"}"""
      }

      entries.mkString("[\n", ",\n", "\n]")
    }

  private def escapeJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

}
