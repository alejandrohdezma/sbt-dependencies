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

  /** Reads dependencies from the given YAML file.
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
    * @return
    *   List of parsed dependencies.
    */
  def read(file: File)(implicit versionFinder: Utils.VersionFinder, logger: Logger): List[Dependency] =
    if (!file.exists()) {
      logger.warn(s"${file.getName} not found. Run `initDependenciesFile` to create it from existing dependencies.")
      Nil
    } else {
      val yaml    = new Yaml()
      val content = IO.read(file)

      Option(yaml.load[java.util.Map[String, java.util.List[String]]](content))
        .map(_.asScala.toList.sortBy(_._1).flatMap { case (group, deps) =>
          deps.asScala.map(line => Dependency.parse(line, group))
        })
        .getOrElse(List.empty)
    }

  /** Writes dependencies to the given YAML file.
    *
    * Dependencies are grouped by their group name and sorted alphabetically within each group.
    *
    * @param dependencies
    *   The list of dependencies to write.
    * @param file
    *   The target file.
    */
  def write(dependencies: List[Dependency], file: File): Unit = {
    val content = dependencies
      .groupBy(_.group)
      .toList
      .sortBy(_._1)
      .map { case (group, deps) =>
        val items = deps.map(_.toLine).sorted.map(d => s"  - $d").mkString("\n")
        s"$group:\n$items"
      }
      .mkString("\n\n")

    IO.write(file, content + "\n")
  }

}
