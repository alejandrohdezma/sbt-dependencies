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

import scala.jdk.CollectionConverters._

import sbt._
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.finders.Utils
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.alejandrohdezma.sbt.dependencies.model.Group
import com.typesafe.config.ConfigFactory

/** Handles reading and writing dependencies to/from the dependencies.conf file.
  *
  * @param file
  *   The dependencies.conf file to read.
  */
final case class DependenciesFile(file: File) {

  /** Whether the file exists. */
  def exists(): Boolean = file.exists()

  /** Reads dependencies for a specific group from the given HOCON file.
    *
    * If the file does not exist an empty list will be returned. The returned dependencies carry their annotations
    * (`note`, `intransitive`, `scala-filter`, `cross-version`) from the file.
    *
    * Variables that don't have a matching entry in `variableResolvers` cause this method to fail with a descriptive
    * error message — strict resolution is enforced here, at the read seam, not inside `Dependency.parse`.
    *
    * The file format is HOCON with group names as top-level keys:
    * {{{
    * sbt-build = [
    *   "org.typelevel::cats-core:2.10.0"
    *   "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"
    * ]
    *
    * my-project = [
    *   "org.scalameta::munit:1.2.1:test"
    * ]
    * }}}
    *
    * @param group
    *   The group to read dependencies for.
    * @param variableResolvers
    *   The map of variable resolvers to use.
    * @return
    *   List of parsed dependencies for the specified group.
    */
  def read(group: Group, variableResolvers: Map[String, OrganizationArtifactName => ModuleID])(implicit
      logger: Logger
  ): List[Dependency] = {
    val deps = readRaw(file).get(group).toList.flatMap(_.dependencies).map(toDependency(_, variableResolvers))
    deps.foreach(validateResolved(_, variableResolvers))
    deps
  }

  /** Parses an `AnnotatedDependency` line into a `Dependency`, applying the entry's annotations and only then resolving
    * any variable. Resolving after annotations are applied means the `OrganizationArtifactName` passed to the variable
    * resolver carries the dep's final `crossVersion` — including the `cross-version` annotation override — rather than
    * the binary/disabled default the line's separator would imply.
    *
    * Combining a variable version with `cross-version = "full"` or `"patch"` is rejected here: sbt's `Organization
    * ArtifactName` constructor is `private[sbt]`, so we can only pass `Binary` or `Disabled` shapes to the resolver,
    * and the canonical BOM resolver (`here-sbt-bom`) only looks up by binary suffix anyway. Failing fast with a clear
    * message is better than silently degrading to `Binary`.
    */
  private def toDependency(
      annotated: AnnotatedDependency,
      variableResolvers: Map[String, OrganizationArtifactName => ModuleID]
  )(implicit logger: Logger): Dependency = {
    val parsed = Dependency.parse(annotated.line)

    val crossVersion = annotated.crossVersion match {
      case Some("full")     => CrossVersion.full
      case Some("binary")   => CrossVersion.binary
      case Some("patch")    => CrossVersion.patch
      case Some("disabled") => CrossVersion.disabled
      case Some(other)      => Utils.fail(s"Invalid cross-version: $other")
      case None             => parsed.crossVersion
    }

    val dep = parsed.withAnnotations(annotated.note, annotated.intransitive, annotated.scalaFilter, crossVersion)

    val supportedInVariable = List(CrossVersion.binary, CrossVersion.disabled)

    if (dep.version.isVariable && !supportedInVariable.contains(dep.crossVersion)) {
      Utils.fail {
        s"Variable '${dep.version.show}' on ${dep.organization}:${dep.name} cannot be combined with " +
          s"cross-version = '${Dependency.crossVersionKeyword(dep.crossVersion).getOrElse("?")}' — " +
          "only 'binary' and 'disabled' are supported when the version is a variable."
      }
    }

    dep.resolveVariable(variableResolvers)
  }

  /** Fails loudly with a descriptive message when a Variable couldn't be resolved. The strict semantic that used to
    * live inside `Dependency.parse` lives here now so the round-trip paths (`format`, the annotation-extraction inside
    * `write`) can tolerate `Variable(name, None)` without failing.
    */
  private def validateResolved(
      dep: Dependency,
      resolvers: Map[String, OrganizationArtifactName => ModuleID]
  )(implicit logger: Logger): Unit = dep.version match {
    case Dependency.Version.Variable(name, None) =>
      val available =
        if (resolvers.isEmpty) "(none defined)"
        else resolvers.keys.mkString(", ")
      Utils.fail(s"Variable '{{$name}}' not found in dependencyVersionVariables. Available: $available")
    case _ => ()
  }

  /** Writes dependencies for a specific group to the given HOCON file.
    *
    * Other groups in the file are preserved. The format (simple vs advanced) of existing groups is preserved, unless
    * scalaVersions or javaVersion is provided, in which case Advanced format is used.
    *
    * Each dep's annotations (`note`, `intransitive`, `scalaFilter`, `crossVersion`) are emitted verbatim. Read-then-
    * write flows (`updateDependencies`, `install`) get this for free since `read` already populates annotations from
    * the file. Callers that build deps from scratch — primarily `initDependenciesFile` — should run them through
    * [[applyExistingAnnotations]] first to preserve user-added annotations across re-runs.
    *
    * @param group
    *   The group to write dependencies for.
    * @param dependencies
    *   The list of dependencies to write. Annotations are emitted as carried by each dep.
    * @param scalaVersions
    *   Optional list of Scala versions to write. If non-empty, Advanced format is used.
    * @param javaVersion
    *   Optional Java target version to write. If defined, Advanced format is used. When the group already exists with a
    *   `java-version`, passing `None` preserves the existing value; passing `Some(v)` overrides it.
    */
  def write(
      group: Group,
      dependencies: List[Dependency],
      scalaVersions: List[String] = Nil,
      javaVersion: Option[String] = None
  )(implicit logger: Logger): Unit =
    if (dependencies.nonEmpty || scalaVersions.nonEmpty || javaVersion.nonEmpty) {
      val existingConfigs = readRaw(file)

      val dependencyLines = dependencies
        .foldLeft(List.empty[Dependency]) { (acc, dep) =>
          if (acc.exists(_.isSameArtifact(dep))) acc else acc :+ dep
        }
        .sorted
        .map(AnnotatedDependency.from)

      val versions = Option(scalaVersions).filter(_.nonEmpty)

      val newConfig =
        existingConfigs.get(group) match {
          case Some(adv: GroupConfig.Advanced) =>
            GroupConfig.Advanced(
              dependencyLines,
              versions.getOrElse(adv.scalaVersions),
              javaVersion.orElse(adv.javaVersion)
            )
          case _ if versions.nonEmpty || javaVersion.nonEmpty =>
            GroupConfig.Advanced(dependencyLines, versions.getOrElse(Nil), javaVersion)
          case _ =>
            GroupConfig.Simple(dependencyLines)
        }

      val updated = existingConfigs + (group -> newConfig)

      val content = updated.toList
        .sortBy(_._1)
        .map { case (g, config) => config.format(g) }
        .mkString("\n\n")

      IO.write(file, content + "\n")
    }

  /** Returns each input dep with annotations from the existing file applied on top, keyed by
    * `(organization, name, configuration)`. Used by `initDependenciesFile` to preserve user-added notes / intransitive
    * flags / scala-filters / cross-version annotations when regenerating the file from build settings. The file's
    * annotation wins per field (`orElse` for `Option` fields, `OR` for `intransitive`); only an explicit
    * `cross-version` annotation in the file overrides the dep's `crossVersion` — when the file has no annotation, the
    * dep's value (which reflects the build's current `CrossVersion`) is kept.
    *
    * Read-then-write flows don't need this — `read` already populates annotations from the file via
    * `Dependency.withAnnotations` in `toDependency`.
    */
  def applyExistingAnnotations(group: Group, deps: List[Dependency])(implicit logger: Logger): List[Dependency] = {
    val existing: Map[(String, String, String), AnnotatedDependency] = readRaw(file)
      .get(group)
      .toList
      .flatMap(_.dependencies)
      .filter(ad => ad.note.isDefined || ad.intransitive || ad.scalaFilter.isDefined || ad.crossVersion.isDefined)
      .flatMap { ad =>
        ad.line match {
          case Dependency.dependencyRegex(org, _, name, _, config) =>
            List((org, name, Option(config).getOrElse("compile")) -> ad)
          case _ => Nil
        }
      }
      .toMap

    deps.map { dep =>
      existing.get((dep.organization, dep.name, dep.configuration)) match {
        case None      => dep
        case Some(ann) =>
          val crossVersion = ann.crossVersion match {
            case Some("full")     => CrossVersion.full
            case Some("binary")   => CrossVersion.binary
            case Some("patch")    => CrossVersion.patch
            case Some("disabled") => CrossVersion.disabled
            case _                => dep.crossVersion
          }
          dep.withAnnotations(
            note = ann.note.orElse(dep.note),
            intransitive = ann.intransitive || dep.intransitive,
            scalaFilter = ann.scalaFilter.orElse(dep.scalaFilter),
            crossVersion = crossVersion
          )
      }
    }
  }

  /** Reads the scalaVersions for a specific group from the given HOCON file.
    *
    * Validates that each version is a valid numeric version format. Invalid versions are logged as warnings and
    * filtered out. Versions without an explicit marker default to `Minor` (`~`) for safety.
    *
    * @param group
    *   The group to read scalaVersions for.
    * @return
    *   List of valid Scala versions, or empty list if not defined.
    */
  def readScalaVersions(group: Group)(implicit logger: Logger): List[Numeric] =
    readRaw(file).get(group).map(_.scalaVersions).getOrElse(Nil).flatMap {
      case Numeric(v) =>
        // Default to Minor marker for Scala versions without explicit marker (safer than NoMarker)
        val version = if (v.marker === Numeric.Marker.NoMarker) v.withMarker(Numeric.Marker.Minor) else v
        List(version)
      case invalid =>
        logger.warn(s"Invalid Scala version format: $invalid")
        Nil
    }

  /** Writes Scala versions for a specific group to the given HOCON file.
    *
    * Other groups and dependencies in the file are preserved. Version markers are preserved when writing. Existing
    * `java-version` is preserved.
    *
    * @param group
    *   The group to write Scala versions for.
    * @param scalaVersions
    *   The list of Scala versions to write (including markers).
    */
  def writeScalaVersions(group: Group, scalaVersions: List[Numeric])(implicit logger: Logger): Unit = {
    val existingConfigs = readRaw(file)

    val newConfig = existingConfigs.get(group) match {
      case Some(existing) =>
        GroupConfig.Advanced(existing.dependencies, scalaVersions.map(_.show), existing.javaVersion)
      case None => GroupConfig.Advanced(Nil, scalaVersions.map(_.show))
    }

    val updated = existingConfigs + (group -> newConfig)

    val content = updated.toList
      .sortBy(_._1)
      .map { case (g, config) => config.format(g) }
      .mkString("\n\n")

    IO.write(file, content + "\n")
  }

  /** Reads the `java-version` for a specific group from the given HOCON file.
    *
    * @param group
    *   The group to read `java-version` for.
    * @return
    *   The configured Java version for the group, or `None` if not set.
    */
  def readJavaVersion(group: Group)(implicit logger: Logger): Option[String] =
    readRaw(file).get(group).flatMap(_.javaVersion)

  /** Sorts dependencies within each group and rewrites the file with consistent formatting.
    *
    * Groups are sorted with `sbt-build` first, then alphabetically. Dependencies within each group are sorted by
    * (configuration, organization, name). All annotations, Scala versions, and format (Simple vs Advanced) are
    * preserved.
    */
  def format()(implicit logger: Logger): Unit = {
    val content = readRaw(file).toList
      .sortBy(_._1)
      .map { case (g, c) => c.sorted.format(g) }
      .mkString("\n\n")

    IO.write(file, content + "\n")
  }

  /** Checks if a group exists in the given HOCON file.
    *
    * @param group
    *   The group to check for.
    * @return
    *   `true` if the group exists in the file, `false` otherwise.
    */
  def hasGroup(group: Group)(implicit logger: Logger): Boolean =
    readRaw(file).contains(group)

  /** Reads the raw HOCON file as a map of groups to group configurations.
    *
    * Supports two formats:
    *   - Simple: group maps to list of strings
    *   - Advanced: group maps to object with "dependencies" key
    */
  private def readRaw(file: File)(implicit logger: Logger): Map[Group, GroupConfig] =
    if (!file.exists()) Map.empty
    else {
      val content = IO.read(file)
      if (content.trim.isEmpty) Map.empty
      else {
        val config = ConfigFactory.parseString(content)

        config
          .root()
          .keySet()
          .asScala
          .map { name =>
            val group = Group(name)
            GroupConfig.parse(config, group) match {
              case Right(groupConfig) => group -> groupConfig
              case Left(error)        => Utils.fail(s"Failed to parse group `$name`: $error")
            }
          }
          .toMap
      }
    }

}

object DependenciesFile {

  def apply(file: File): DependenciesFile = new DependenciesFile(file)

}
