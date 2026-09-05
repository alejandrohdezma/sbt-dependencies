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

import sbt._
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.finders.Utils
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.model.DependencyOps._
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.alejandrohdezma.sbt.dependencies.model.Group

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
    val deps = readGroups().get(group).toList.flatMap(_.dependencies).map(toDependency(_, variableResolvers))
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
    *
    * BOM-managed versions (`*`) get the same `full`/`patch` rejection (BOM entries are looked up by binary suffix) plus
    * one of their own: a `bom`-configured entry cannot itself use `*`, since BOM coordinates are what `*` versions are
    * resolved from. Resolution of `*` doesn't happen here — `dependenciesFromBom` needs this `read` to find the group's
    * BOM coordinates first — but at the seams that have the flattened pins (via `Dependency.resolveBom`).
    */
  private def toDependency(
      annotated: AnnotatedDependency,
      variableResolvers: Map[String, OrganizationArtifactName => ModuleID]
  )(implicit logger: Logger): Dependency = {
    val parsed = Dependency.parseOrFail(annotated.line)

    val crossVersion = annotated.crossVersion match {
      case Some(keyword) =>
        Dependency.Cross.fromKeyword(keyword).getOrElse(Utils.fail(s"Invalid cross-version: $keyword"))
      case None => parsed.crossVersion
    }

    val dep = parsed.withAnnotations(
      annotated.note, annotated.intransitive, annotated.scalaFilter, crossVersion, annotated.overrides
    )

    val supportedInVariable = List[Dependency.Cross](Dependency.Cross.Binary, Dependency.Cross.Disabled)

    if (dep.version.isVariable && !supportedInVariable.contains(dep.crossVersion)) {
      Utils.fail {
        s"Variable '${dep.version.show}' on ${dep.organization}:${dep.name} cannot be combined with " +
          s"cross-version = '${Dependency.crossVersionKeyword(dep.crossVersion).getOrElse("?")}' — " +
          "only 'binary' and 'disabled' are supported when the version is a variable."
      }
    }

    Dependency.validateBomRestrictionsOrFail(dep)

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
    * Other groups in the file are preserved, except fully-empty ones (no dependencies, no Scala/Java settings), which
    * are dropped. The format (simple vs advanced) of existing groups is preserved, unless scalaVersions or javaVersion
    * is provided, in which case Advanced format is used.
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
  ): Unit =
    if (dependencies.nonEmpty || scalaVersions.nonEmpty || javaVersion.nonEmpty) {
      val existingConfigs = readGroups()

      val dependencyLines = dependencies
        .foldLeft(List.empty[Dependency]) { (acc, dep) =>
          if (acc.exists(_.isSameArtifact(dep))) acc else acc :+ dep
        }
        .sorted
        .map(AnnotatedDependency.from)

      val versions = Option(scalaVersions.flatMap(version => Numeric.unapply(version))).filter(_.nonEmpty)

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

      IO.write(file, GroupConfig.render(updated) + "\n")
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
  def applyExistingAnnotations(group: Group, deps: List[Dependency]): List[Dependency] = {
    val existing: Map[(String, String, String), AnnotatedDependency] = readGroups()
      .get(group)
      .toList
      .flatMap(_.dependencies)
      .filter { ad =>
        ad.note.isDefined || ad.intransitive || ad.overrides || ad.scalaFilter.isDefined || ad.crossVersion.isDefined
      }
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
          val crossVersion = ann.crossVersion.flatMap(Dependency.Cross.fromKeyword).getOrElse(dep.crossVersion)
          dep.withAnnotations(
            note = ann.note.orElse(dep.note),
            intransitive = ann.intransitive || dep.intransitive,
            scalaFilter = ann.scalaFilter.orElse(dep.scalaFilter),
            crossVersion = crossVersion,
            overrides = ann.overrides || dep.overrides
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
  def readScalaVersions(group: Group): List[Numeric] =
    readGroups().get(group).map(_.scalaVersions).getOrElse(Nil).map { version =>
      // Default to Minor marker for Scala versions without explicit marker (safer than NoMarker)
      if (version.marker === Numeric.Marker.NoMarker) version.withMarker(Numeric.Marker.Minor) else version
    }

  /** Writes Scala versions for a specific group to the given HOCON file.
    *
    * Other groups and dependencies in the file are preserved, except fully-empty groups (no dependencies, no Scala/Java
    * settings), which are dropped. Version markers are preserved when writing. Existing `java-version` is preserved.
    *
    * @param group
    *   The group to write Scala versions for.
    * @param scalaVersions
    *   The list of Scala versions to write (including markers).
    */
  def writeScalaVersions(group: Group, scalaVersions: List[Numeric]): Unit = {
    val existingConfigs = readGroups()

    val newConfig = existingConfigs.get(group) match {
      case Some(existing) =>
        GroupConfig.Advanced(existing.dependencies, scalaVersions, existing.javaVersion)
      case None => GroupConfig.Advanced(Nil, scalaVersions)
    }

    val updated = existingConfigs + (group -> newConfig)

    IO.write(file, GroupConfig.render(updated) + "\n")
  }

  /** Reads the `java-version` for a specific group from the given HOCON file.
    *
    * @param group
    *   The group to read `java-version` for.
    * @return
    *   The configured Java version for the group, or `None` if not set.
    */
  def readJavaVersion(group: Group): Option[String] =
    readGroups().get(group).flatMap(_.javaVersion)

  /** Sorts dependencies within each group and rewrites the file with consistent formatting.
    *
    * Groups are sorted with `sbt-build` first, then alphabetically. Dependencies within each group are sorted by
    * (configuration, organization, name). All annotations, Scala versions, and format (Simple vs Advanced) are
    * preserved. Fully-empty groups (no dependencies, no Scala/Java settings) are dropped.
    */
  def format(): Unit =
    IO.write(file, GroupConfig.render(readGroups().map { case (group, config) => group -> config.sorted }) + "\n")

  /** Checks if a group exists in the given HOCON file.
    *
    * @param group
    *   The group to check for.
    * @return
    *   `true` if the group exists in the file, `false` otherwise.
    */
  def hasGroup(group: Group): Boolean =
    readGroups().contains(group)

  /** Reads the Scala versions declared by every group in the file, as authored (markers preserved, groups with none
    * mapping to an empty list). Unlike [[readScalaVersions(group:* the single-group overload]] it does not default a
    * missing marker, so callers see exactly what the file declares.
    *
    * @return
    *   A map of every group to its declared Scala versions.
    */
  def readScalaVersions(): Map[Group, List[Numeric]] =
    readGroups().map { case (group, config) => group -> config.scalaVersions }

  /** Reads the HOCON file as a map of groups to group configurations.
    *
    * Supports two formats:
    *   - Simple: group maps to list of strings
    *   - Advanced: group maps to object with "dependencies" key
    *
    * Requires no `Logger`, so it can be called at build-load time (outside a Setting). A structural parse error throws
    * with the offending group and reason.
    */
  def readGroups(): Map[Group, GroupConfig] =
    if (!file.exists()) Map.empty
    else GroupConfig.parseAll(IO.read(file)).fold(sys.error, identity)

}

object DependenciesFile {

  def apply(file: File): DependenciesFile = new DependenciesFile(file)

  /** The project's dependencies file at its canonical build-root location (`project/dependencies.conf`).
    *
    * Resolved from the current working directory, so it is meaningful at build-load time (in `build.sbt` or a `project`
    * source file) where `baseDirectory` is not yet available. Reading it never requires a `Logger`.
    */
  lazy val default: DependenciesFile = DependenciesFile(new File("project/dependencies.conf"))

}
