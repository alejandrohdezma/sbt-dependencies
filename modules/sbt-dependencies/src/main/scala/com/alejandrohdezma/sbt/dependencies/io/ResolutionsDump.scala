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

import scala.util.control.NonFatal

import sbt.Keys._
import sbt.io.Hash
import sbt.{Keys => _, _}

import com.alejandrohdezma.sbt.dependencies.Keys
import com.alejandrohdezma.sbt.dependencies.model.Eq._

/** The `target/sbt-dependencies/.sbt-resolutions` dump: every project's BOM pins and variable resolutions, written on
  * load so editor tooling (the VSCode extension) can display resolved versions next to `*` and `{{variable}}`
  * dependencies without resolving BOMs itself.
  */
object ResolutionsDump {

  /** One flattened `<dependencyManagement>` entry: the concrete (already Scala-suffixed) artifact a BOM pins. */
  case class Pin(organization: String, name: String, version: String)

  /** A BOM's coordinate and its flattened pins, stored once at the top level and referenced by key from every project
    * that sees it, so a large BOM isn't duplicated across projects.
    */
  case class Bom(organization: String, name: String, version: String, entries: Seq[Pin])

  /** A dependency whose `{{variable}}` version resolved, with the variable's name and the version it resolved to. */
  case class VariableResolution(organization: String, name: String, cross: Boolean, variable: String, version: String)

  /** One group's dump data: the Scala binary versions it builds for (the loaded one first), its visible BOMs in
    * precedence order (keyed, alongside their flattened contents), and its resolved variable dependencies.
    */
  case class ProjectResolutions(
      group: String,
      scalaBinaryVersions: Seq[String],
      boms: Seq[(String, Bom)],
      variables: Seq[VariableResolution]
  )

  /** Writes the resolutions dump for the loaded build, collecting every project's `dependencyResolutions` setting.
    *
    * Skipped when the build has no `dependencies.conf`. Never fails the load: any error is logged as a warning and the
    * state is returned untouched. In the meta-build session the same code lands the dump (with the `sbt-build` group)
    * in `project/target/sbt-dependencies/.sbt-resolutions`.
    */
  def write(state: State): State =
    try {
      val extracted = Project.extract(state)

      val base   = extracted.get(ThisBuild / baseDirectory)
      val isMeta = base.name.equalsIgnoreCase("project")
      val conf   = if (isMeta) base / "dependencies.conf" else base / "project" / "dependencies.conf"

      if (conf.exists()) {
        val projects = extracted.structure.allProjectRefs
          .flatMap(ref => extracted.getOpt(ref / Keys.dependencyResolutions))
          .flatten

        val json = toJson(Hash.toHex(Hash(conf)), projects)

        IO.write(base / "target" / "sbt-dependencies" / ".sbt-resolutions", json)
      }

      state
    } catch {
      case NonFatal(e) =>
        state.log.warn(s"Failed to write the sbt-dependencies resolutions dump: ${e.getMessage}")
        state
    }

  /** Renders the dump as deterministic JSON: BOMs and projects sorted by key/group (first occurrence wins on
    * duplicates), a project's `boms` list kept in precedence order, and groups with neither BOMs nor variables omitted.
    */
  def toJson(sourceHash: String, projects: Seq[ProjectResolutions]): String = {
    val boms = distinctByKey(projects.flatMap(_.boms)).sortBy(_._1)

    val groups = distinctByKey(projects.map(p => p.group -> p)).filter { case (_, p) =>
      p.boms.nonEmpty || p.variables.nonEmpty
    }
      .sortBy(_._1)

    val bomEntries = boms.map { case (key, bom) =>
      val entries = array {
        bom.entries.map { pin =>
          s"""{"organization": "${escapeJson(pin.organization)}", "name": "${escapeJson(pin.name)}",""" +
            s""" "version": "${escapeJson(pin.version)}"}"""
        }
      }

      s"""    "${escapeJson(key)}": {"organization": "${escapeJson(bom.organization)}",""" +
        s""" "name": "${escapeJson(bom.name)}", "version": "${escapeJson(bom.version)}", "entries": $entries}"""
    }

    val projectEntries = groups.map { case (group, project) =>
      val sbvs = array(project.scalaBinaryVersions.map(v => s""""${escapeJson(v)}""""))
      val keys = array(project.boms.map { case (key, _) => s""""${escapeJson(key)}"""" })

      val variables = array {
        project.variables.map { v =>
          s"""{"organization": "${escapeJson(v.organization)}", "name": "${escapeJson(v.name)}",""" +
            s""" "cross": ${v.cross}, "variable": "${escapeJson(v.variable)}", "version": "${escapeJson(v.version)}"}"""
        }
      }

      s"""    "${escapeJson(group)}": {"scalaBinaryVersions": $sbvs, "boms": $keys, "variables": $variables}"""
    }

    def block(entries: Seq[String]): String =
      if (entries.isEmpty) "{}" else entries.mkString("{\n", ",\n", "\n  }")

    s"""{
       |  "version": 1,
       |  "sourceHash": "${escapeJson(sourceHash)}",
       |  "boms": ${block(bomEntries)},
       |  "projects": ${block(projectEntries)}
       |}
       |""".stripMargin
  }

  /** Renders a JSON array from already-rendered values. */
  private def array(values: Seq[String]): String = values.mkString("[", ", ", "]")

  /** Keeps the first occurrence of each key, preserving order. */
  private def distinctByKey[A](entries: Seq[(String, A)]): Seq[(String, A)] =
    entries.foldLeft(Vector.empty[(String, A)]) { (acc, entry) =>
      if (acc.exists(_._1 === entry._1)) acc else acc :+ entry
    }

  /** Escapes a string for embedding in a JSON literal. */
  private[io] def escapeJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

}
