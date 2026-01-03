/*
 * Copyright 2025 Alejandro Hernández <https://github.com/alejandrohdezma>
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

import scala.jdk.CollectionConverters._

import sbt._
import sbt.util.Logger

import org.yaml.snakeyaml.Yaml

/** Handles reading and writing dependencies to/from the dependencies.yaml file. */
object DependenciesFile {

  /** Reads dependencies for a specific group from the given YAML file.
    *
    * If the file does not exist an empty list will be returned.
    *
    * The file format is YAML with group names as top-level keys and lists of dependency strings as values:
    * {{{
    * sbt-build:
    *   - org.typelevel::cats-core:2.10.0
    *   - ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin
    *
    * my-project:
    *   - org.scalameta::munit:1.2.1:test
    * }}}
    *
    * @param file
    *   The dependencies.yaml file to read.
    * @param group
    *   The group to read dependencies for.
    * @return
    *   List of parsed dependencies for the specified group.
    */
  def read(file: File, group: String)(implicit versionFinder: Utils.VersionFinder, logger: Logger): List[Dependency] =
    if (!file.exists()) {
      logger.warn(s"${file.getName} not found. Run `initDependenciesFile` to create it from existing dependencies.")
      Nil
    } else {
      readRaw(file).get(group).toList.flatten.map(Dependency.parse(_, group))
    }

  /** Writes dependencies for a specific group to the given YAML file.
    *
    * Other groups in the file are preserved.
    *
    * @param file
    *   The target file.
    * @param group
    *   The group to write dependencies for.
    * @param dependencies
    *   The list of dependencies to write.
    */
  def write(file: File, group: String, dependencies: List[Dependency]): Unit = {
    val existing = readRaw(file)
    val updated  = existing + (group -> dependencies.map(_.toLine).sorted)

    val content = updated.toList
      .sortBy(_._1)
      .map { case (g, lines) =>
        s"$g:\n${lines.map(d => s"  - $d").mkString("\n")}"
      }
      .mkString("\n\n")

    IO.write(file, content + "\n")
  }

  /** Reads the raw YAML file as a map of group names to dependency lines. */
  private def readRaw(file: File): Map[String, List[String]] =
    if (!file.exists()) Map.empty
    else {
      val yaml = new Yaml()
      Option(yaml.load[java.util.Map[String, java.util.List[String]]](IO.read(file)))
        .map(_.asScala.toMap.map { case (k, v) => k -> v.asScala.toList })
        .getOrElse(Map.empty)
    }

}
