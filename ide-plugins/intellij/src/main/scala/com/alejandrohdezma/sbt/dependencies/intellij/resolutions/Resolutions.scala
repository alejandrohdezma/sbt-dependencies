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

package com.alejandrohdezma.sbt.dependencies.intellij.resolutions

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters._
import scala.util.Try

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject

/** The `.sbt-resolutions` dump the sbt plugin writes on every load: each project's visible BOM pins and resolved
  * `{{variable}}` versions, keyed the same way the VSCode extension reads them.
  */
object Resolutions {

  /** One flattened `dependencyManagement` entry: a concrete (already Scala-suffixed) artifact a BOM pins. */
  final case class BomPin(organization: String, name: String, version: String)

  /** A BOM's coordinate and its flattened pins. */
  final case class Bom(organization: String, name: String, version: String, entries: List[BomPin])

  /** A dependency whose `{{variable}}` version resolved, matched by organization + name + cross. */
  final case class VariableResolution(
      organization: String,
      name: String,
      cross: Boolean,
      variable: String,
      version: String
  )

  /** One group's resolutions: its Scala binary versions, visible BOM keys (precedence order) and resolved variables. */
  final case class ProjectResolutions(
      scalaBinaryVersions: List[String],
      boms: List[String],
      variables: List[VariableResolution]
  )

  /** A parsed dump file. */
  final case class Dump(sourceHash: Option[String], boms: Map[String, Bom], projects: Map[String, ProjectResolutions])

  /** A resolved `*` version together with the BOM that pins it. */
  final case class Pin(version: String, bomOrganization: String, bomName: String, bomVersion: String)

  /** Parses a dump, returning `None` on malformed JSON or an unsupported `version` so callers silently disable. */
  def parse(json: String): Option[Dump] =
    Try {
      val config = ConfigFactory.parseString(json)

      Option.when(config.getInt("version") == 1) {
        val boms = config.getObject("boms").asScala.toMap.map { case (key, value) =>
          val bom = value.asInstanceOf[ConfigObject].toConfig

          key -> Bom(
            bom.getString("organization"),
            bom.getString("name"),
            bom.getString("version"),
            bom.getConfigList("entries").asScala.toList.map { entry =>
              BomPin(entry.getString("organization"), entry.getString("name"), entry.getString("version"))
            }
          )
        }

        val projects = config.getObject("projects").asScala.toMap.map { case (key, value) =>
          val project = value.asInstanceOf[ConfigObject].toConfig

          key -> ProjectResolutions(
            project.getStringList("scalaBinaryVersions").asScala.toList,
            project.getStringList("boms").asScala.toList,
            project.getConfigList("variables").asScala.toList.map { variable =>
              VariableResolution(
                variable.getString("organization"), variable.getString("name"), variable.getBoolean("cross"),
                variable.getString("variable"), variable.getString("version")
              )
            }
          )
        }

        Dump(Try(config.getString("sourceHash")).toOption, boms, projects)
      }
    }.toOption.flatten

  /** The lookup for the `dependencies.conf` at `conf` against its current `text`, or `None` when neither the main nor
    * the meta build has written a dump (feature off). Parsed dumps are cached and only re-read when their modification
    * time changes; staleness is an exact SHA-1 mismatch between `text` and the hash recorded in the dump.
    */
  def lookupFor(conf: Path, text: String): Option[Lookup] = {
    val relative = Path.of("target", "sbt-dependencies", ".sbt-resolutions")

    val main = cached(conf.getParent.getParent.resolve(relative))
    val meta = cached(conf.getParent.resolve(relative))

    Option.when(main.isDefined || meta.isDefined) {
      val sourceHash = main.flatMap(_.sourceHash).orElse(meta.flatMap(_.sourceHash))

      new Lookup(main, meta, stale = sourceHash.exists(_ != sha1(text)))
    }
  }

  /** Answers `*` and `{{variable}}` resolutions, routing the `sbt-build` group to the meta-build dump and every other
    * group to the main one, with first-BOM-wins precedence — matching the sbt plugin.
    */
  final class Lookup(main: Option[Dump], meta: Option[Dump], val stale: Boolean) {

    /** The BOM pin for a `*` version, if any BOM visible to `group` pins the artifact. */
    def resolveWildcard(group: String, organization: String, name: String, isCross: Boolean): Option[Pin] =
      dumpFor(group).flatMap { dump =>
        dump.projects.get(group).flatMap { project =>
          val concrete = project.scalaBinaryVersions.headOption
            .filter(_ => isCross)
            .fold(name)(sbv => s"${name}_$sbv")

          pinsFor(group, dump, project).get(s"$organization:$concrete")
        }
      }

    /** The resolved version and variable name for a `{{variable}}` dependency, if the dump recorded it. */
    def resolveVariable(
        group: String,
        organization: String,
        name: String,
        isCross: Boolean
    ): Option[VariableResolution] =
      dumpFor(group)
        .flatMap(_.projects.get(group))
        .flatMap(_.variables.find(v => v.organization == organization && v.name == name && v.cross == isCross))

    private def dumpFor(group: String): Option[Dump] = if (group == "sbt-build") meta else main

    private val pinsCache = new ConcurrentHashMap[String, Map[String, Pin]]()

    private def pinsFor(group: String, dump: Dump, project: ProjectResolutions): Map[String, Pin] =
      pinsCache.computeIfAbsent(
        group,
        _ =>
          project.boms
            .flatMap(key => dump.boms.get(key))
            .flatMap(bom =>
              bom.entries.map(pin =>
                s"${pin.organization}:${pin.name}" -> Pin(pin.version, bom.organization, bom.name, bom.version)
              )
            )
            .foldLeft(Map.empty[String, Pin]) { case (map, (key, pin)) =>
              if (map.contains(key)) map else map.updated(key, pin)
            }
      )

  }

  private val dumpCache = new ConcurrentHashMap[Path, (Long, Option[Dump])]()

  private def cached(path: Path): Option[Dump] = {
    val mtime = Try(Files.getLastModifiedTime(path).toMillis).getOrElse(0L)

    if (mtime == 0L) {
      dumpCache.remove(path)
      None
    } else
      dumpCache
        .compute(
          path,
          (_, previous) =>
            previous match {
              case (`mtime`, dump) => (mtime, dump)
              case _               => (mtime, Try(Files.readString(path, StandardCharsets.UTF_8)).toOption.flatMap(parse))
            }
        )
        ._2
  }

  private def sha1(text: String): String =
    MessageDigest
      .getInstance("SHA-1")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"$byte%02x")
      .mkString

}
