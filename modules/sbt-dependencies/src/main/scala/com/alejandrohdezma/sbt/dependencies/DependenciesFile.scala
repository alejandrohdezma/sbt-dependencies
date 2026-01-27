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

package com.alejandrohdezma.sbt.dependencies

import scala.jdk.CollectionConverters._

import sbt._
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.Eq._
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
    * @param variableResolvers
    *   The map of variable resolvers to use.
    * @return
    *   List of parsed dependencies for the specified group.
    */
  def read(file: File, group: String, variableResolvers: Map[String, OrganizationArtifactName => ModuleID])(implicit
      versionFinder: Utils.VersionFinder,
      logger: Logger
  ): List[Dependency] =
    if (!file.exists()) {
      logger.warn(s"${file.getName} not found. Run `initDependenciesFile` to create it from existing dependencies.")
      Nil
    } else {
      readRaw(file).get(group).map(_.dependencies).toList.flatten.map(Dependency.parse(_, group, variableResolvers))
    }

  /** Writes dependencies for a specific group to the given YAML file.
    *
    * Other groups in the file are preserved. The format (simple vs advanced) of existing groups is preserved, unless
    * scalaVersions is provided, in which case Advanced format is used.
    *
    * @param file
    *   The target file.
    * @param group
    *   The group to write dependencies for.
    * @param dependencies
    *   The list of dependencies to write.
    * @param scalaVersions
    *   Optional list of Scala versions to write. If non-empty, Advanced format is used.
    */
  def write(file: File, group: String, dependencies: List[Dependency], scalaVersions: List[String] = Nil)(implicit
      logger: Logger
  ): Unit =
    if (dependencies.nonEmpty || scalaVersions.nonEmpty) {
      val existingConfigs = readRaw(file)

      val dependencyLines = dependencies.distinct.sorted.map(_.toLine)

      val newConfig =
        if (scalaVersions.nonEmpty) GroupConfig.Advanced(dependencyLines, scalaVersions)
        else
          existingConfigs.get(group) match {
            case Some(adv: GroupConfig.Advanced) => GroupConfig.Advanced(dependencyLines, adv.scalaVersions)
            case _                               => GroupConfig.Simple(dependencyLines)
          }

      val updated = existingConfigs + (group -> newConfig)

      val content = updated.toList
        .sortBy(_._1)
        .map { case (g, config) => config.format(g) }
        .mkString("\n\n")

      IO.write(file, content + "\n")
    }

  /** Reads the scalaVersions for a specific group from the given YAML file.
    *
    * Validates that each version is a valid numeric version format. Invalid versions are logged as warnings and
    * filtered out. Versions without an explicit marker default to `Minor` (`~`) for safety.
    *
    * @param file
    *   The dependencies.yaml file to read.
    * @param group
    *   The group to read scalaVersions for.
    * @return
    *   List of valid Scala versions, or empty list if not defined.
    */
  def readScalaVersions(file: File, group: String)(implicit logger: Logger): List[Numeric] =
    readRaw(file).get(group).map(_.scalaVersions).getOrElse(Nil).flatMap {
      case Numeric(v) =>
        // Default to Minor marker for Scala versions without explicit marker (safer than NoMarker)
        val version = if (v.marker === Numeric.Marker.NoMarker) v.copy(marker = Numeric.Marker.Minor) else v
        List(version)
      case invalid =>
        logger.warn(s"Invalid Scala version format: $invalid")
        Nil
    }

  /** Writes Scala versions for a specific group to the given YAML file.
    *
    * Other groups and dependencies in the file are preserved. Version markers are preserved when writing.
    *
    * @param file
    *   The target file.
    * @param group
    *   The group to write Scala versions for.
    * @param scalaVersions
    *   The list of Scala versions to write (including markers).
    */
  def writeScalaVersions(file: File, group: String, scalaVersions: List[Numeric])(implicit logger: Logger): Unit = {
    val existingConfigs = readRaw(file)

    val newConfig = existingConfigs.get(group) match {
      case Some(existing) => GroupConfig.Advanced(existing.dependencies, scalaVersions.map(_.show))
      case None           => GroupConfig.Advanced(Nil, scalaVersions.map(_.show))
    }

    val updated = existingConfigs + (group -> newConfig)

    val content = updated.toList
      .sortBy(_._1)
      .map { case (g, config) => config.format(g) }
      .mkString("\n\n")

    IO.write(file, content + "\n")
  }

  /** Checks if a group exists in the given YAML file.
    *
    * @param file
    *   The dependencies.yaml file to check.
    * @param group
    *   The group to check for.
    * @return
    *   `true` if the group exists in the file, `false` otherwise.
    */
  def hasGroup(file: File, group: String)(implicit logger: Logger): Boolean =
    readRaw(file).contains(group)

  /** Reads the raw YAML file as a map of group names to group configurations.
    *
    * Supports two formats:
    *   - Simple: group maps to list of strings
    *   - Advanced: group maps to object with "dependencies" key
    */
  private def readRaw(file: File)(implicit logger: Logger): Map[String, GroupConfig] =
    if (!file.exists()) Map.empty
    else {
      val yaml = new Yaml()

      Option(yaml.load[java.util.Map[String, Object]](IO.read(file)))
        .map(_.asScala.toMap)
        .getOrElse(Map.empty)
        .map { case (group, value) =>
          GroupConfig.parse(value) match {
            case Right(config) => group -> config
            case Left(error)   => Utils.fail(s"Failed to parse group `$group`: $error")
          }
        }
    }

}
