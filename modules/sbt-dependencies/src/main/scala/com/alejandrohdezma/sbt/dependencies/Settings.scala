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

import sbt.Defaults.sbtPluginExtra
import sbt.Keys._
import sbt.util.Logger
import sbt.{Keys => _, _}

import com.alejandrohdezma.sbt.dependencies.bom.Bom
import com.alejandrohdezma.sbt.dependencies.bom.BomReader
import com.alejandrohdezma.sbt.dependencies.bom.ModuleFetcher
import com.alejandrohdezma.sbt.dependencies.finders.Utils
import com.alejandrohdezma.sbt.dependencies.io.DependenciesFile
import com.alejandrohdezma.sbt.dependencies.io.ResolutionsDump
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.alejandrohdezma.sbt.dependencies.model.Group
import com.alejandrohdezma.sbt.dependencies.model.Group._

class Settings {

  /** Whether the build is an SBT build. */
  val isSbtBuild: Def.Initialize[Boolean] = Def.setting {
    (ThisBuild / baseDirectory).value.name.equalsIgnoreCase("project")
  }

  /** The current group of the build. */
  val currentGroup: Def.Initialize[Group] = Def.setting {
    if (isSbtBuild.value) `sbt-build` else Group(name.value)
  }

  /** The path to the dependencies.conf file. */
  val dependenciesFile: Def.Initialize[DependenciesFile] = Def.setting {
    if (isSbtBuild.value) DependenciesFile((ThisBuild / baseDirectory).value / "dependencies.conf")
    else DependenciesFile((ThisBuild / baseDirectory).value / "project" / "dependencies.conf")
  }

  /** The list of dependencies read from the file (with variables resolved). */
  val dependenciesFromFile: Def.Initialize[List[Dependency]] = Def.setting {
    implicit val logger: Logger = sLog.value

    val variableResolvers = Keys.dependencyVersionVariables.value

    dependenciesFile.value.read(currentGroup.value, variableResolvers)
  }

  /** Scala versions from the `common-settings` group, used as defaults for every non-meta project.
    *
    * Returns `Nil` when in the meta-build: `common-settings` describes defaults for main-build projects, and the
    * meta-build (`project/`) layer must keep using SBT's plugin convention (2.12).
    */
  val commonScalaVersions: Def.Initialize[Seq[String]] = Def.setting {
    if (isSbtBuild.value) Nil
    else dependenciesFile.value.readScalaVersions(`common-settings`).map(_.toVersionString)
  }

  /** Scala versions from the current project's group (only in normal build, not meta-build).
    *
    * Returns `Nil` when in the meta-build for the same reason as [[commonScalaVersions]].
    */
  val projectScalaVersions: Def.Initialize[Seq[String]] = Def.setting {
    if (isSbtBuild.value) Nil
    else dependenciesFile.value.readScalaVersions(currentGroup.value).map(_.toVersionString)
  }

  /** Java target version from the `common-settings` group, used as a default for every non-meta project. */
  val commonJavaVersion: Def.Initialize[Option[String]] = Def.setting {
    if (isSbtBuild.value) None
    else dependenciesFile.value.readJavaVersion(`common-settings`)
  }

  /** Java target version from the current project's group. */
  val projectJavaVersion: Def.Initialize[Option[String]] = Def.setting {
    if (isSbtBuild.value) None
    else dependenciesFile.value.readJavaVersion(currentGroup.value)
  }

  /** Gets the inherited dependencies from other projects (recursively). */
  val inheritedDependencies = Def.settingDyn {
    thisProject.value.dependencies.foldLeft(Def.setting(Seq.empty[ModuleID])) { (acc, classPathDependency) =>
      Def.setting {
        val configuration: PartialFunction[String, String] = classPathDependency.configuration.map { c =>
          c.split(';')
            .map(_.trim())
            .map {
              case configRegex(from, to) =>
                { case s if s === from => to }: PartialFunction[String, String]
              case value =>
                { case s if value.contains(s) => value }: PartialFunction[String, String]
            }
            .reduceLeft(_ orElse _)
        }.getOrElse({ case "compile" => "compile" }: PartialFunction[String, String])

        def filterByConfiguration(modules: Seq[ModuleID]): Seq[ModuleID] =
          modules.flatMap {
            case module if configuration.isDefinedAt(module.configurations.getOrElse("compile")) =>
              List(module.withConfigurations(Some(configuration(module.configurations.getOrElse("compile")))))
            case _ =>
              Nil
          }

        // Direct dependencies from the dependent project
        val direct = filterByConfiguration((classPathDependency.project / sbt.Keys.libraryDependencies).value)

        // Transitive dependencies (what the dependent project inherited)
        val transitive = filterByConfiguration((classPathDependency.project / Keys.inheritedDependencies).value)

        acc.value ++ direct ++ transitive
      }
    }
  }

  /** The list of library dependencies to add to the project.
    *
    * Merges `common-settings.dependencies` with the project group's own dependencies. When both groups declare a
    * dependency with the same `(organization, name)`, the project entry wins regardless of configuration.
    *
    * BOM-managed versions (`*`) are resolved here against [[dependenciesFromBom]] (the group's flattened BOM pins, in
    * declaration order, so the first BOM pinning an artifact wins). A `*` dependency that no BOM pins fails the build
    * with a descriptive message.
    *
    * In the meta-build, only the project group is read — `common-settings.dependencies` are not for plugins.
    */
  val moduleIdsFromFile: Def.Initialize[Seq[ModuleID]] = Def.setting {
    val sbtV                    = (pluginCrossBuild / sbtBinaryVersion).value
    val scalaV                  = (update / scalaBinaryVersion).value
    implicit val logger: Logger = sLog.value

    val variableResolvers = Keys.dependencyVersionVariables.value
    val file              = dependenciesFile.value
    val bomPins           = Keys.dependenciesFromBom.value

    def readGroup(group: Group): Seq[ModuleID] =
      file
        .read(group, variableResolvers)
        .filterNot(_.configuration === "bom") // `:bom` entries go to `dependenciesFromBom`, not `libraryDependencies`
        .filter(_.matchesScalaVersion(scalaV))
        .filter(dep => dep.scalaFilter.forall(scalaV.startsWith))
        .map(resolveBomVersion(_, bomPins, scalaV))
        .map(_.toModuleID(sbtV, scalaV))

    val projectDeps = readGroup(currentGroup.value)

    val commonDeps =
      if (isSbtBuild.value) Seq.empty
      else readGroup(`common-settings`)

    val projectKeys = projectDeps.map(m => (m.organization, m.name)).toSet
    val merged      = commonDeps.filterNot(m => projectKeys.contains((m.organization, m.name))) ++ projectDeps

    lazy val self =
      sbtPluginExtra("com.alejandrohdezma" % "sbt-dependencies" % BuildInfo.version, sbtV, scalaV)

    // Add self when in meta-build so the plugin is available in the build definition
    merged ++ (if (isSbtBuild.value) Seq(self) else Seq.empty)
  }

  /** The flattened managed dependencies of every BOM this project declares with the `bom` configuration — its own group
    * plus, for non-meta projects, `common-settings` (so an org-wide BOM in `common-settings` is inherited by every
    * module). Each BOM's parent chain and `<scope>import</scope>` tree is resolved to a flat list of pins by
    * `BomReader`.
    *
    * Pins keep BOM declaration order — the project group's BOMs, then `common-settings`', then the groups of every
    * project this one depends on (transitively, via `dependsOn`) — each flattened to entries sorted by
    * organization/name. BOM-managed versions (`*`) resolve to the first matching pin, so the closest BOM pinning an
    * artifact wins (Maven's import semantics): the project's own BOMs take precedence over inherited ones.
    *
    * Inheritance follows the project graph regardless of the `dependsOn` configuration mapping (a test-scoped
    * dependency contributes its pins to every scope): pins only select versions, they never add artifacts. Inherited
    * groups are flattened with *this* project's Scala version, so cross-Scala-version `dependsOn` still yields
    * correctly-suffixed pins.
    *
    * It reads poms at load, so it Nil-fasts when neither the project nor its `dependsOn` graph declares `:bom` entries.
    */
  val dependenciesFromBom: Def.Initialize[Seq[ModuleID]] = Def.setting {
    val scalaV = (update / scalaBinaryVersion).value

    implicit val logger: Logger         = sLog.value
    implicit val fetcher: ModuleFetcher = bomFetcher.value

    visibleBoms.value.flatMap(BomReader.read(_, scalaV)).distinct
  }

  /** The `:bom` coordinates visible to this project, in precedence order: its own group, then (for non-meta projects)
    * `common-settings`, then the groups of every project it depends on (transitively, via `dependsOn`), all flattened
    * with this project's sbt/Scala versions.
    */
  private def visibleBoms: Def.Initialize[Seq[ModuleID]] = Def.settingDyn {
    val dependencyRefs = buildDependencies.value.classpathTransitive.getOrElse(thisProjectRef.value, Nil)

    val inheritedGroups = dependencyRefs.map(ref => Def.setting(Group((ref / name).value))).join

    Def.setting {
      val sbtV   = (pluginCrossBuild / sbtBinaryVersion).value
      val scalaV = (update / scalaBinaryVersion).value

      val variableResolvers = Keys.dependencyVersionVariables.value

      implicit val logger: Logger = sLog.value

      def bomsOf(group: Group): Seq[ModuleID] =
        dependenciesFile.value
          .read(group, variableResolvers)
          .filter(_.configuration === "bom")
          .map(_.toModuleID(sbtV, scalaV))

      bomsOf(currentGroup.value)
        .++(if (isSbtBuild.value) Nil else bomsOf(`common-settings`))
        .++(inheritedGroups.value.flatMap(bomsOf))
        .distinct
    }
  }

  /** A pom fetcher over the project's `update` resolvers, with sbt's conventional credential files loaded first so BOMs
    * on authenticated repositories resolve.
    */
  private[dependencies] def bomFetcher: Def.Initialize[ModuleFetcher] = Def.setting {
    val repositories = (update / resolvers).value ++ (update / appResolvers).value.getOrElse(Seq.empty)
    val options      = (update / updateOptions).value
    val paths        = (update / ivyPaths).value

    implicit val logger: Logger = sLog.value

    BomReader.loadCredentials

    ModuleFetcher.fromIvy(PluginCompat.ivySbt(repositories, options, paths, logger))
  }

  /** The structured data behind `target/sbt-dependencies/.sbt-resolutions`: this project's visible BOMs (in precedence
    * order, each flattened to its pins) and resolved `{{variable}}` dependencies, plus the same for `common-settings`
    * (whose entry is best-effort: its `*` and variable versions are computed against `common-settings`' own BOMs and
    * this project's resolvers).
    *
    * BOM keys are `organization:name:version@scalaBinaryVersion` — opaque to consumers, unique per flattening. Pin
    * flattening reuses `BomReader`'s JVM-wide pom cache, already primed by [[dependenciesFromBom]] at load.
    */
  val dependencyResolutions: Def.Initialize[Seq[ResolutionsDump.ProjectResolutions]] = Def.setting {
    val sbtV   = (pluginCrossBuild / sbtBinaryVersion).value
    val scalaV = (update / scalaBinaryVersion).value

    implicit val logger: Logger         = sLog.value
    implicit val fetcher: ModuleFetcher = bomFetcher.value

    val variableResolvers = Keys.dependencyVersionVariables.value

    val binaryVersions = {
      val cross = crossScalaVersions.value.map(CrossVersion.binaryScalaVersion)
      (scalaV +: cross.filterNot(_ === scalaV)).distinct
    }

    def flatten(bom: ModuleID): (String, ResolutionsDump.Bom) = {
      val entries =
        BomReader.read(bom, scalaV).map(pin => ResolutionsDump.Pin(pin.organization, pin.name, pin.revision))

      val key = s"${bom.organization}:${bom.name}:${bom.revision}@$scalaV"

      key -> ResolutionsDump.Bom(bom.organization, bom.name, bom.revision, entries)
    }

    def variablesOf(group: Group): Seq[ResolutionsDump.VariableResolution] =
      dependenciesFile.value.read(group, variableResolvers).flatMap { dependency =>
        dependency.version match {
          case Dependency.Version.Variable(variable, Some(resolved)) =>
            List {
              ResolutionsDump.VariableResolution(
                dependency.organization, dependency.name, dependency.isCross, variable, resolved.toVersionString
              )
            }
          case _ => Nil
        }
      }

    val own = ResolutionsDump.ProjectResolutions(
      currentGroup.value.name,
      binaryVersions,
      visibleBoms.value.map(flatten),
      variablesOf(currentGroup.value)
    )

    if (isSbtBuild.value) List(own)
    else {
      val commonBoms = dependenciesFile.value
        .read(`common-settings`, variableResolvers)
        .filter(_.configuration === "bom")
        .map(_.toModuleID(sbtV, scalaV))

      val common = ResolutionsDump.ProjectResolutions(
        `common-settings`.name,
        binaryVersions,
        commonBoms.map(flatten),
        variablesOf(`common-settings`)
      )

      List(own, common)
    }
  }

  /** The BOM pins added to `dependencyOverrides`: [[dependenciesFromBom]] deduplicated by `organization:name` keeping
    * the first entry. The dedupe matters because [[dependenciesFromBom]] can carry the same module at two versions when
    * two BOMs conflict, and coursier's force-versions map would let the last one win, inverting the first-BOM-wins
    * contract `*` versions follow.
    */
  val dependencyOverridesFromBom: Def.Initialize[Seq[ModuleID]] = Def.setting {
    Bom.dedupeByModule(Keys.dependenciesFromBom.value)
  }

  /** Resolves a BOM-managed version (`*`) against the group's flattened BOM pins, failing the build when no BOM pins
    * the artifact. Dependencies with any other version shape pass through untouched.
    */
  private[dependencies] def resolveBomVersion(dependency: Dependency, pins: Seq[ModuleID], scalaBinaryVersion: String)(
      implicit logger: Logger
  ): Dependency = {
    val resolved = dependency.resolveBom(pins, scalaBinaryVersion)

    resolved.version match {
      case Dependency.Version.Bom(None) =>
        val artifact = if (resolved.isCross) s"${resolved.name}_$scalaBinaryVersion" else resolved.name
        Utils.fail {
          s"${resolved.organization}:${resolved.name} declares version '*' but no BOM visible to this project pins " +
            s"${resolved.organization}:$artifact. Declare a dependency with the 'bom' configuration that manages it."
        }
      case _ => resolved
    }
  }

  /** Regex to match configuration transformations like `test->test`. */
  private val configRegex = """(.*)->(.*)""".r

}

object Settings extends Settings
