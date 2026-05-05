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

import scala.Console._
import scala.collection.mutable.ListBuffer
import scala.util.Try

import sbt.Keys._
import sbt.librarymanagement.CrossVersion
import sbt.{Keys => _, _}

import com.alejandrohdezma.sbt.dependencies.constraints.ConfigCache
import com.alejandrohdezma.sbt.dependencies.constraints.PostUpdateHook
import com.alejandrohdezma.sbt.dependencies.constraints.ScalafixMigration
import com.alejandrohdezma.sbt.dependencies.finders.IgnoreFinder
import com.alejandrohdezma.sbt.dependencies.finders.MigrationFinder
import com.alejandrohdezma.sbt.dependencies.finders.PinFinder
import com.alejandrohdezma.sbt.dependencies.finders.RetractionFinder
import com.alejandrohdezma.sbt.dependencies.finders.Utils
import com.alejandrohdezma.sbt.dependencies.finders.VersionFinder
import com.alejandrohdezma.sbt.dependencies.io.AnnotatedDependency
import com.alejandrohdezma.sbt.dependencies.io.DependenciesFile
import com.alejandrohdezma.sbt.dependencies.io.DependencyDiff
import com.alejandrohdezma.sbt.dependencies.io.Scalafmt
import com.alejandrohdezma.sbt.dependencies.io.UpdateScript
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.alejandrohdezma.sbt.dependencies.model.Group
import com.alejandrohdezma.sbt.dependencies.model.Group._
import coursier.MavenRepository
import coursier.Repository

/** SBT commands for managing dependencies. */
class Commands {

  /** All commands provided by this plugin. */
  val all = Seq(initDependenciesFile, formatDependenciesFile, updateAllDependencies, updateSbtPlugin,
    updateBuildDependencies, updateCommonDependencies, installBuildDependencies, installCommonDependencies, updateSbt,
    updateBuildScalaVersions, updateCommonScalaVersions, updateScalafmtVersion, disableEvictionWarnings,
    enableEvictionWarnings, snapshotDependencies, snapshotBuildDependencies, snapshotCommonDependencies,
    snapshotSbtPlugin, snapshotSbtVersion, computeDependencyDiff, computePostUpdateHooks)

  /** Creates (or recreates) the dependencies.conf file based on current project dependencies and Scala versions.
    *
    * Accepts optional flags:
    *   - `--scala-versions` — include scala versions in the output
    *   - `--java-version` — include the Java target version (read from `javacOptions`) in the output
    *   - `--compiler-plugins` — include Scala compiler plugin dependencies (emitted with the `:compiler-plugin` config
    *     keyword); when omitted, compiler plugins are skipped
    *   - `--all` — enables all of the above
    */
  lazy val initDependenciesFile = Command.args("initDependenciesFile", "<options>") { (state, args) =>
    implicit val logger: Logger = state.log

    val includeAll             = args.contains("--all")
    val includeScalaVersions   = includeAll || args.contains("--scala-versions")
    val includeJavaVersion     = includeAll || args.contains("--java-version")
    val includeCompilerPlugins = includeAll || args.contains("--compiler-plugins")

    val project = Project.extract(state)

    val base = project.get(ThisBuild / baseDirectory)

    val isSbtBuild = base.name.equalsIgnoreCase("project")

    val pluginOrg  = project.get(Keys.sbtDependenciesPluginOrganization)
    val pluginName = project.get(Keys.sbtDependenciesPluginName)

    val newGroups: Set[Group] = project.structure.allProjectRefs.map { ref =>
      if (isSbtBuild) `sbt-build` else Group(project.get(ref / name))
    }.toSet

    val moduleIDsByGroup: Map[Group, List[ModuleID]] =
      project.structure.allProjectRefs.flatMap { ref =>
        val group: Group = if (isSbtBuild) `sbt-build` else Group(project.get(ref / name))
        project.get(ref / libraryDependencies).map(group -> _)
      }.groupBy(_._1)
        .mapValues(_.map(_._2).toList)
        .mapValues(_.filterNot(_.organization === "org.scala-lang"))
        .mapValues(_.filterNot(dep => dep.organization === pluginOrg && dep.name === pluginName))
        .mapValues { modules =>
          if (includeCompilerPlugins) modules
          else modules.filterNot(_.configurations.contains(Dependency.CompilerPluginConfiguration))
        }
        .toMap

    val dependenciesByGroup: Map[Group, List[Dependency]] =
      moduleIDsByGroup.mapValues(_.flatMap(Dependency.fromModuleID(_).toList))

    // For each ModuleID whose CrossVersion differs from the default that `Dependency.toModuleID` would produce, capture
    // a `cross-version` annotation so the round-trip preserves the user's original choice (e.g. compiler plugins
    // declared with `cross CrossVersion.full`).
    val crossVersionAnnotationsByGroup
        : Map[Group, Map[AnnotatedDependency.NoteKey, AnnotatedDependency.AnnotationData]] =
      moduleIDsByGroup.mapValues { modules =>
        modules.flatMap { m =>
          Dependency.fromModuleID(m).flatMap { dep =>
            val defaultKeyword = if (dep.isCross) "binary" else "disabled"
            crossVersionKeyword(m.crossVersion).filter(_ !== defaultKeyword).map { keyword =>
              AnnotatedDependency.NoteKey(dep.organization, dep.name, dep.configuration) ->
                AnnotatedDependency.AnnotationData(None, intransitive = false, None, Some(keyword))
            }
          }
        }.toMap
      }

    // Gather Scala versions for each group (skip in meta-build, always 2.12)
    val scalaVersionsByGroup: Map[Group, List[String]] =
      if (!includeScalaVersions || isSbtBuild) Map.empty
      else
        project.structure.allProjectRefs.map { ref =>
          Group(project.get(ref / name)) -> project.get(ref / crossScalaVersions).toList
        }.toMap

    // Gather Java target version for each group from `javacOptions` (skip in meta-build)
    val javaVersionByGroup: Map[Group, Option[String]] =
      if (!includeJavaVersion || isSbtBuild) Map.empty
      else
        project.structure.allProjectRefs.map { ref =>
          val options = Try(project.runTask(ref / javacOptions, state)).toOption.map(_._2).getOrElse(Seq.empty)
          Group(project.get(ref / name)) -> javaVersionFromOptions(options)
        }.toMap

    // Check if all projects share the same Scala versions
    val uniqueVersionSets = scalaVersionsByGroup.values.map(_.sorted).toSet
    val sharedVersions    = if (uniqueVersionSets.size === 1) uniqueVersionSets.head else Nil

    // Check if all projects share the same Java version
    val uniqueJavaVersions = javaVersionByGroup.values.toSet
    val sharedJavaVersion  = if (uniqueJavaVersions.size === 1) uniqueJavaVersions.head else None

    val file = DependenciesFile {
      if (isSbtBuild) base / "dependencies.conf"
      else base / "project" / "dependencies.conf"
    }

    // Write `common-settings` with shared scala/java versions (if any)
    if (sharedVersions.nonEmpty || sharedJavaVersion.nonEmpty) {
      file.write(`common-settings`, Nil, sharedVersions, sharedJavaVersion)
    }

    // Write `sbt-build` only when it actually carries plugin dependencies
    val sbtBuildDeps = dependenciesByGroup.getOrElse(`sbt-build`, Nil)
    if (newGroups.contains(`sbt-build`) && sbtBuildDeps.nonEmpty) {
      file.write(
        `sbt-build`,
        sbtBuildDeps,
        additionalAnnotations = crossVersionAnnotationsByGroup.getOrElse(`sbt-build`, Map.empty)
      )
    }

    // Write each project group's dependencies (and scala/java versions if not shared)
    newGroups.filterNot(_ === `sbt-build`).foreach { group =>
      val deps          = dependenciesByGroup.getOrElse(group, Nil)
      val scalaVersions = if (sharedVersions.isEmpty) scalaVersionsByGroup.getOrElse(group, Nil) else Nil
      val javaVersion   = if (sharedJavaVersion.isEmpty) javaVersionByGroup.getOrElse(group, None) else None

      file.write(
        group,
        deps,
        scalaVersions,
        javaVersion,
        additionalAnnotations = crossVersionAnnotationsByGroup.getOrElse(group, Map.empty)
      )
    }

    logger.info("✎ Created project/dependencies.conf file with your dependencies")
    logger.info("ℹ Remember to remove any `libraryDependencies +=` or `addSbtPlugin` settings from your build files")

    val flagsString = args.mkString(" ")

    if (isSbtBuild) state
    else if (!isPluginInMetaBuild(state)) state
    else {
      val composite = ("reload plugins" +: s"initDependenciesFile $flagsString" :+ "reload return").mkString("; ")
      state.copy(remainingCommands = Exec(composite, None) +: state.remainingCommands)
    }
  }

  /** Sorts dependencies within each group in the dependencies.conf file and rewrites it with consistent formatting. */
  lazy val formatDependenciesFile = Command.command("formatDependenciesFile") { state =>
    implicit val logger: Logger = state.log

    val base = Project.extract(state).get(ThisBuild / baseDirectory)

    val isSbtBuild = base.name.equalsIgnoreCase("project")

    val file = DependenciesFile {
      if (isSbtBuild) base / "dependencies.conf"
      else base / "project" / "dependencies.conf"
    }

    if (file.exists()) {
      file.format()
      logger.info("✎ Formatted project/dependencies.conf")
    } else {
      logger.warn("project/dependencies.conf not found")
    }

    state
  }

  /** Updates everything: plugin, Scala versions, dependencies, scalafmt, and SBT version. */
  lazy val updateAllDependencies = Command.command("updateAllDependencies") { state =>
    val base = Project.extract(state).get(ThisBuild / baseDirectory)

    val steps = List(
      "disableEvictionWarnings",    // Lower eviction errors so snapshots can resolve
      "snapshotDependencies",       // Record resolved main-build deps before changes
      "snapshotCommonDependencies", // Record declared common-settings deps before changes
      "snapshotBuildDependencies",  // Record declared meta-build deps before changes
      "snapshotSbtPlugin",          // Record plugin version before changes
      "updateSbtPlugin",            // Update sbt-dependencies (or wrapper) plugin version
      "updateCommonScalaVersions",  // Update Scala versions in common-settings group
      "updateBuildScalaVersions",   // Update Scala versions in project/dependencies.conf
      "updateCommonDependencies",   // Update dependencies in common-settings group
      "updateBuildDependencies",    // Update dependencies in project/dependencies.conf
      "updateScalafmtVersion",      // Update scalafmt version in .scalafmt.conf files
      "updateScalaVersions",        // Update Scala versions in main build
      "updateDependencies",         // Update dependencies in main build
      "reload",                     // Reload build (resets session settings)
      "snapshotSbtVersion",         // Record sbt version before changes
      "updateSbt",                  // Update sbt version in build.properties
      "disableEvictionWarnings",    // Re-lower eviction errors (lost after reload)
      "computeDependencyDiff",      // Compute main diff and merge sbt-build/common diffs
      "computePostUpdateHooks",     // Match hooks/migrations against diff, write JSON output
      "enableEvictionWarnings"      // Restore eviction errors
    )

    runStepsSafely(steps: _*)(state, base / "target" / "sbt-dependencies")
  }

  /** Updates the configured SBT plugin. Checks `project/project/plugins.sbt` first, falling back to
    * `project/plugins.sbt`.
    */
  lazy val updateSbtPlugin = Command.command("updateSbtPlugin") { state =>
    implicit val logger: Logger = state.log

    val project = Project.extract(state)

    implicit val configCache: ConfigCache =
      ConfigCache(project.get(ThisBuild / baseDirectory) / "target" / "sbt-dependencies" / "config-cache")

    val retractionFinder = RetractionFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateRetractions))

    implicit val versionFinder: VersionFinder = getVersionFinder(state, scalaVersion = "2.12.0")

    implicit val migrationFinder: MigrationFinder =
      MigrationFinder.fromUrls(project.get(ThisBuild / Keys.dependencyMigrations))

    val base       = project.get(ThisBuild / baseDirectory)
    val pluginOrg  = project.get(Keys.sbtDependenciesPluginOrganization)
    val pluginName = project.get(Keys.sbtDependenciesPluginName)

    val metaBuild    = base / "project" / "project" / "plugins.sbt"
    val regularBuild = base / "project" / "plugins.sbt"

    val escapedOrg = pluginOrg.replace(".", """\.""")

    val pluginRegex =
      s"""addSbtPlugin\\s*\\(\\s*"$escapedOrg"\\s*%\\s*"$pluginName"\\s*%\\s*"([^"]+)"\\s*\\).*""".r

    def updatePluginInFile(file: File): Option[State] = {
      lazy val lines = IO.readLines(file)

      if (!file.exists() || !lines.exists(pluginRegex.findFirstIn(_).isDefined)) None
      else {
        logger.info(s"\n↻ Checking for new versions of the `$pluginName` plugin\n")

        val updatedLines = lines.map {
          case line @ pluginRegex(Numeric(current)) =>
            val dependency =
              Dependency.WithNumericVersion(pluginOrg, pluginName, current, isCross = false, "sbt-plugin")

            val latest = dependency.findLatestVersion.version

            if (latest.isSameVersion(current)) {
              retractionFinder.warnIfRetracted(dependency)
              logger.info(s" ↳ $GREEN✓$RESET $GREEN${current.show}$RESET")
              (line, false)
            } else {
              logger.info(s" ↳ $YELLOW⬆$RESET $YELLOW${current.show}$RESET -> $CYAN${latest.show}$RESET")
              (s"""addSbtPlugin("$pluginOrg" % "$pluginName" % "${latest.show}")""", true)
            }
          case line => (line, false)
        }

        IO.writeLines(file, updatedLines.map(_._1))

        Some(state)
      }
    }

    updatePluginInFile(metaBuild)
      .orElse(updatePluginInFile(regularBuild))
      .getOrElse {
        logger.warn(s"Could not find `$pluginName` plugin in `project/project/plugins.sbt` or `project/plugins.sbt`")
        state
      }
  }

  /** Resolves latest versions for dependencies in the `sbt-build` group of `project/dependencies.conf`.
    *
    * Reads the file directly from the main build, resolves each dependency to its latest version using Coursier, and
    * writes the updated versions back. Retracted versions are warned about but not applied.
    */
  lazy val updateBuildDependencies = updateGroupDependencies("updateBuildDependencies", `sbt-build`)

  /** Resolves latest versions for dependencies in the `common-settings` group of `project/dependencies.conf`. */
  lazy val updateCommonDependencies = updateGroupDependencies("updateCommonDependencies", `common-settings`)

  private def updateGroupDependencies(commandName: String, group: Group): Command =
    Command.command(commandName) { state =>
      withDependenciesFile(state, group) {
        implicit versionFinder => implicit migrationFinder => retractionFinder => (project, file) =>
          implicit val logger: Logger = state.log

          val deps = file.readAnnotated(group, Map.empty)

          if (deps.nonEmpty) {
            logger.info(s"\n↻ Updating dependencies for `${group.name}` in project/dependencies.conf\n")

            val updated = Utils.resolveLatestVersions(deps, project.get(ThisBuild / Keys.dependencyResolverParallelism))

            updated.foreach(retractionFinder.warnIfRetracted(_))

            file.write(group, updated)
          }

          state
      }
    }

  /** Resolves latest Scala patch versions in the `sbt-build` group of `project/dependencies.conf`.
    *
    * Reads the file directly from the main build, and updates each Scala version to the latest patch release within the
    * same minor series (e.g. 2.12.x, 3.3.x).
    *
    * `sbt-build` no longer accepts scala settings; this remains as a no-op for clean orchestration in
    * `updateAllDependencies`.
    */
  lazy val updateBuildScalaVersions = updateGroupScalaVersions("updateBuildScalaVersions", `sbt-build`)

  /** Resolves latest Scala patch versions in the `common-settings` group of `project/dependencies.conf`. */
  lazy val updateCommonScalaVersions = updateGroupScalaVersions("updateCommonScalaVersions", `common-settings`)

  private def updateGroupScalaVersions(commandName: String, group: Group): Command =
    Command.command(commandName) { state =>
      withDependenciesFile(state, group) { implicit versionFinder => implicit migrationFinder => _ => (_, file) =>
        implicit val logger: Logger = state.log

        val versions = file.readScalaVersions(group)

        if (versions.nonEmpty) {
          logger.info(s"\n↻ Updating Scala versions for `${group.name}` in project/dependencies.conf\n")

          val updated = versions.map { version =>
            val latest = Utils.findLatestScalaVersion(version)

            if (latest === version) {
              logger.info(s" ↳ $GREEN✓$RESET $GREEN$version$RESET")
              version
            } else {
              logger.info(
                s" ↳ $YELLOW⬆$RESET $YELLOW$version$RESET -> $CYAN$latest$RESET"
              )
              latest
            }
          }

          file.writeScalaVersions(group, updated)
        }

        state
      }
    }

  /** Installs a dependency in the `sbt-build` group of `project/dependencies.conf` from the main build.
    *
    * Accepts a dependency string with or without a version (e.g. `org::name:1.0.0` or `org::name`). When the version is
    * omitted, the latest stable version is resolved automatically.
    */
  lazy val installBuildDependencies = installGroupDependency("installBuildDependencies", `sbt-build`)

  /** Installs a dependency in the `common-settings` group of `project/dependencies.conf` from the main build. */
  lazy val installCommonDependencies = installGroupDependency("installCommonDependencies", `common-settings`)

  private def installGroupDependency(commandName: String, group: Group): Command =
    Command.single(commandName) { case (state, dependency) =>
      implicit val logger: Logger = state.log

      withDependenciesFile(state, group) { implicit versionFinder => _ => _ => (_, file) =>
        val dependencies = file.read(group, Map.empty)

        val dep = Dependency.parseIncludingMissingVersion(dependency)

        logger.info(s"➕ [${group.name}] $YELLOW${dep.toLine}$RESET")

        file.write(group, dependencies.filterNot(_.isSameArtifact(dep)) :+ dep)

        state
      }
    }

  /** Updates scalafmt version in `.scalafmt.conf` to the latest version. */
  lazy val updateScalafmtVersion = Command.command("updateScalafmtVersion") { state =>
    implicit val logger: Logger = state.log

    val project = Project.extract(state)

    val base = project.get(ThisBuild / baseDirectory)

    implicit val configCache: ConfigCache =
      ConfigCache(base / "target" / "sbt-dependencies" / "config-cache")

    logger.info("\n↻ Checking for new versions of Scalafmt\n")

    implicit val retractionFinder = RetractionFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateRetractions))

    implicit val versionFinder: VersionFinder = getVersionFinder(state, scalaVersion = "2.13.0")

    implicit val migrationFinder: MigrationFinder =
      MigrationFinder.fromUrls(project.get(ThisBuild / Keys.dependencyMigrations))

    Scalafmt.updateVersion(base)

    state
  }

  /** Updates SBT version in `project/build.properties` to the latest version. */
  lazy val updateSbt = Command.command("updateSbt") { state =>
    implicit val logger: Logger = state.log

    val project = Project.extract(state)

    val base = project.get(ThisBuild / baseDirectory)

    implicit val configCache: ConfigCache =
      ConfigCache(base / "target" / "sbt-dependencies" / "config-cache")

    implicit val migrationFinder: MigrationFinder =
      MigrationFinder.fromUrls(project.get(ThisBuild / Keys.dependencyMigrations))

    val buildProperties = base / "project" / "build.properties"

    if (!buildProperties.exists()) {
      logger.warn("project/build.properties not found")
      state
    } else {
      val lines = IO.readLines(buildProperties)

      val sbtVersionRegex = """sbt\.version\s*=\s*(.+)""".r

      if (!lines.exists(sbtVersionRegex.findFirstIn(_).isDefined)) {
        logger.warn("sbt.version not found in project/build.properties")
        state
      } else {
        logger.info("\n↻ Checking for new versions of SBT\n")

        val retractionFinder = RetractionFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateRetractions))

        implicit val versionFinder: VersionFinder = getVersionFinder(state, scalaVersion = "2.12.0")

        val updatedLines = lines.map {
          case line @ sbtVersionRegex(Numeric(current)) =>
            val dependency = Dependency.sbt(current)

            val latest = dependency.findLatestVersion.version

            if (latest.isSameVersion(current)) {
              retractionFinder.warnIfRetracted(dependency)
              logger.info(s" ↳ $GREEN✓$RESET $GREEN${current.show}$RESET")
              (line, false)
            } else {
              logger.info(s" ↳ $YELLOW⬆$RESET $YELLOW${current.show}$RESET -> $CYAN${latest.show}$RESET")
              (s"sbt.version=$latest", true)
            }
          case line => (line, false)
        }

        IO.writeLines(buildProperties, updatedLines.map(_._1))

        state
      }
    }
  }

  /** Downgrades eviction errors to info level, preventing them from failing the build. */
  lazy val disableEvictionWarnings = Command.command("disableEvictionWarnings") { state =>
    Command.process("set ThisBuild / evictionErrorLevel := Level.Info", state)
  }

  /** Restores eviction warnings to error level, causing eviction issues to fail the build. */
  lazy val enableEvictionWarnings = Command.command("enableEvictionWarnings") { state =>
    Command.process("set ThisBuild / evictionErrorLevel := Level.Error", state)
  }

  /** Snapshots all resolved dependencies (including transitives) for every project to
    * `target/sbt-dependencies/.sbt-dependency-snapshot`.
    */
  lazy val snapshotDependencies = Command.command("snapshotDependencies") { state =>
    Try {
      val snapshot = generateSnapshot(state)

      val outputDir =
        Project.extract(state).get(ThisBuild / baseDirectory) / "target" / "sbt-dependencies"

      DependencyDiff.writeSnapshot(outputDir / ".sbt-dependency-snapshot", snapshot)
    }.onError { case e =>
      state.log.trace(e)
      state.log.error("Unable to generate dependency snapshot")
    }

    state
  }

  /** Snapshots declared meta-build dependencies from `project/dependencies.conf` for later diff computation.
    *
    * Reads the `sbt-build` group and writes the current versions to `target/sbt-dependencies/.sbt-build-snapshot`. This
    * snapshot is later consumed by `computeDependencyDiff` to produce a unified diff that includes both main-build and
    * meta-build changes.
    */
  lazy val snapshotBuildDependencies =
    snapshotGroupDependencies("snapshotBuildDependencies", `sbt-build`, ".sbt-build-snapshot", "build")

  /** Snapshots declared `common-settings` dependencies from `project/dependencies.conf` for later diff computation.
    *
    * The snapshot is folded into every non-meta project's diff in `computeDependencyDiff`, so updates to a shared
    * dependency surface as per-project diff entries (and trigger per-project scalafix migrations).
    */
  lazy val snapshotCommonDependencies =
    snapshotGroupDependencies("snapshotCommonDependencies", `common-settings`, ".sbt-common-snapshot", "common")

  private def snapshotGroupDependencies(
      commandName: String,
      group: Group,
      snapshotFileName: String,
      label: String
  ): Command =
    Command.command(commandName) { state =>
      Try {
        withDependenciesFile(state, group) { _ => _ => _ => (project, file) =>
          implicit val logger: Logger = state.log

          val dependencies = file.read(group, Map.empty)

          if (dependencies.nonEmpty) {
            val snapshot: Map[Group, Set[DependencyDiff.ResolvedDep]] = Map(
              group -> dependencies.map(DependencyDiff.ResolvedDep.from).toSet
            )

            val outputFile =
              project.get(ThisBuild / baseDirectory) / "target" / "sbt-dependencies" / snapshotFileName

            DependencyDiff.writeSnapshot(outputFile, snapshot)
          }

          state
        }
      }.onError { case e =>
        state.log.trace(e)
        state.log.error(s"Unable to generate $label dependency snapshot")
      }

      state
    }

  /** Snapshots the current SBT plugin version for later diff computation.
    *
    * Reads the plugin version from `project/project/plugins.sbt` or `project/plugins.sbt` and writes it to
    * `target/sbt-dependencies/.sbt-plugin-snapshot`. This snapshot is later consumed by `computeDependencyDiff` to
    * detect plugin version changes.
    */
  lazy val snapshotSbtPlugin = Command.command("snapshotSbtPlugin") { state =>
    Try {
      readPluginVersion(state).foreach { pluginDep =>
        val outputFile =
          Project.extract(state).get(ThisBuild / baseDirectory) / "target" / "sbt-dependencies" / ".sbt-plugin-snapshot"

        DependencyDiff.writeSnapshot(
          outputFile,
          Map[Group, Set[DependencyDiff.ResolvedDep]](`sbt-build` -> Set(pluginDep))
        )
      }
    }.onError { case e =>
      state.log.trace(e)
      state.log.error("Unable to generate plugin snapshot")
    }

    state
  }

  /** Snapshots the current SBT version for later diff computation.
    *
    * Reads the SBT version from `project/build.properties` and writes it to
    * `target/sbt-dependencies/.sbt-version-snapshot`. This snapshot is later consumed by `computeDependencyDiff` to
    * detect SBT version changes.
    */
  lazy val snapshotSbtVersion = Command.command("snapshotSbtVersion") { state =>
    Try {
      readSbtVersion(state).foreach { sbtDep =>
        val outputFile =
          Project
            .extract(state)
            .get(ThisBuild / baseDirectory) / "target" / "sbt-dependencies" / ".sbt-version-snapshot"

        DependencyDiff.writeSnapshot(
          outputFile,
          Map[Group, Set[DependencyDiff.ResolvedDep]](`sbt-build` -> Set(sbtDep))
        )
      }
    }.onError { case e =>
      state.log.trace(e)
      state.log.error("Unable to generate SBT version snapshot")
    }

    state
  }

  /** Computes a unified dependency diff by merging main-build and meta-build changes.
    *
    * Compares the current resolved dependencies against the snapshots taken before updates, merges the `sbt-build`
    * group diff from `project/dependencies.conf`, and writes the combined result to
    * `target/sbt-dependencies/.sbt-dependency-diff`. Cleans up snapshot files after processing.
    */
  lazy val computeDependencyDiff = Command.command("computeDependencyDiff") { state =>
    Try {
      implicit val logger: Logger = state.log

      val project = Project.extract(state)

      val base = project.get(ThisBuild / baseDirectory)

      val outputDir = base / "target" / "sbt-dependencies"

      val snapshotFile = outputDir / ".sbt-dependency-snapshot"

      // Merge sbt-build snapshot from project/dependencies.conf
      val (buildBefore, buildAfter) = {
        val file = DependenciesFile(base / "project" / "dependencies.conf")

        val buildSnapshotFile = outputDir / ".sbt-build-snapshot"
        val before            = DependencyDiff.readSnapshot(buildSnapshotFile).getOrElse(`sbt-build`, Set.empty)
        val after             = file.read(`sbt-build`, Map.empty).map(DependencyDiff.ResolvedDep.from).toSet

        IO.delete(buildSnapshotFile)

        // Merge plugin snapshot (before) and current plugin version (after)
        val pluginSnapshotFile = outputDir / ".sbt-plugin-snapshot"
        val pluginBefore       = DependencyDiff.readSnapshot(pluginSnapshotFile).getOrElse(`sbt-build`, Set.empty)
        val pluginAfter        = readPluginVersion(state).toList.toSet

        IO.delete(pluginSnapshotFile)

        // Merge sbt version snapshot (before) and current sbt version (after)
        val sbtVersionSnapshotFile = outputDir / ".sbt-version-snapshot"
        val sbtBefore              = DependencyDiff.readSnapshot(sbtVersionSnapshotFile).getOrElse(`sbt-build`, Set.empty)
        val sbtAfter               = readSbtVersion(state).toList.toSet

        IO.delete(sbtVersionSnapshotFile)

        (before ++ pluginBefore ++ sbtBefore, after ++ pluginAfter ++ sbtAfter)
      }

      // Merge common-settings snapshot — folded into every non-meta project's diff
      val (commonBefore, commonAfter) = {
        val file = DependenciesFile(base / "project" / "dependencies.conf")

        val commonSnapshotFile = outputDir / ".sbt-common-snapshot"
        val before             = DependencyDiff.readSnapshot(commonSnapshotFile).getOrElse(`common-settings`, Set.empty)
        val after              = file.read(`common-settings`, Map.empty).map(DependencyDiff.ResolvedDep.from).toSet

        IO.delete(commonSnapshotFile)

        (before, after)
      }

      val before = DependencyDiff.readSnapshot(snapshotFile)
      val after  = generateSnapshot(state)

      // Fold common-settings into each main-build project's snapshot so updates appear per-project.
      def foldCommon(snap: Map[Group, Set[DependencyDiff.ResolvedDep]], common: Set[DependencyDiff.ResolvedDep]) =
        snap.map { case (group, deps) => group -> (deps ++ common) }

      val diffs = DependencyDiff.compute(
        foldCommon(before, commonBefore) + (`sbt-build` -> buildBefore),
        foldCommon(after, commonAfter) + (`sbt-build`   -> buildAfter)
      )

      if (diffs.nonEmpty)
        IO.write(outputDir / ".sbt-dependency-diff", DependencyDiff.toHocon(diffs))

      IO.delete(snapshotFile)
    }.onError { case e => state.log.warn(s"computeDependencyDiff: ${e.getMessage}") }

    state
  }

  /** Reads the dependency diff and matches it against post-update hooks and scalafix migrations.
    *
    * Writes a JSON file to `target/sbt-dependencies/.sbt-post-update-hooks` listing scripts to run.
    */
  lazy val computePostUpdateHooks = Command.command("computePostUpdateHooks") { state =>
    Try {
      implicit val logger: Logger = state.log

      val project = Project.extract(state)

      val base      = project.get(ThisBuild / baseDirectory)
      val outputDir = base / "target" / "sbt-dependencies"
      val diffFile  = outputDir / ".sbt-dependency-diff"

      if (diffFile.exists()) {
        implicit val configCache: ConfigCache = ConfigCache(outputDir / "config-cache")

        val diffs = DependencyDiff.readDiff(diffFile)

        val hooks = PostUpdateHook.loadFromUrls(project.get(ThisBuild / Keys.dependencyPostUpdateHooks))

        val migrations = ScalafixMigration.loadFromUrls(project.get(ThisBuild / Keys.dependencyScalafixMigrations))

        val scripts = UpdateScript.fromHooks(hooks, diffs) ++ UpdateScript.fromMigrations(migrations, diffs)

        if (scripts.nonEmpty) {
          val json = UpdateScript.toJson(scripts)

          IO.write(outputDir / ".sbt-post-update-hooks", json)

          logger.info(s"✎ Wrote ${scripts.size} post-update hook(s) to $outputDir/.sbt-post-update-hooks")
        }
      }
    }.onError { case e => state.log.warn(s"Unable to compute post-update hooks: ${e.getMessage}") }

    state
  }

  private def isPluginInMetaBuild(state: State): Boolean = {
    val project    = Project.extract(state)
    val base       = project.get(ThisBuild / baseDirectory)
    val pluginOrg  = project.get(Keys.sbtDependenciesPluginOrganization)
    val pluginName = project.get(Keys.sbtDependenciesPluginName)
    val escapedOrg = pluginOrg.replace(".", """\.""")

    val pluginRegex =
      s"""addSbtPlugin\\s*\\(\\s*"$escapedOrg"\\s*%\\s*"$pluginName"\\s*%\\s*"([^"]+)"\\s*\\).*""".r

    val metaBuild = base / "project" / "project" / "plugins.sbt"

    metaBuild.exists() && IO.readLines(metaBuild).exists(pluginRegex.findFirstIn(_).isDefined)
  }

  /** Reads the current plugin version from `project/project/plugins.sbt` or `project/plugins.sbt`.
    *
    * Returns the plugin as a [[DependencyDiff.ResolvedDep]] if found, or `None` if neither file contains a matching
    * `addSbtPlugin` line.
    */
  private def readPluginVersion(state: State): Option[DependencyDiff.ResolvedDep] = {
    val project = Project.extract(state)

    val base       = project.get(ThisBuild / baseDirectory)
    val pluginOrg  = project.get(Keys.sbtDependenciesPluginOrganization)
    val pluginName = project.get(Keys.sbtDependenciesPluginName)

    val escapedOrg = pluginOrg.replace(".", """\.""")

    val pluginRegex =
      s"""addSbtPlugin\\s*\\(\\s*"$escapedOrg"\\s*%\\s*"$pluginName"\\s*%\\s*"([^"]+)"\\s*\\).*""".r

    val metaBuild    = base / "project" / "project" / "plugins.sbt"
    val regularBuild = base / "project" / "plugins.sbt"

    def extractFrom(file: File): Option[DependencyDiff.ResolvedDep] =
      if (!file.exists()) None
      else
        IO.readLines(file).collectFirst { case pluginRegex(version) =>
          DependencyDiff.ResolvedDep(pluginOrg, pluginName, version)
        }

    extractFrom(metaBuild).orElse(extractFrom(regularBuild))
  }

  /** Reads the current SBT version from `project/build.properties`.
    *
    * Returns the SBT version as a [[DependencyDiff.ResolvedDep]] if found, or `None` if the file does not exist or does
    * not contain a `sbt.version` entry.
    */
  private def readSbtVersion(state: State): Option[DependencyDiff.ResolvedDep] = {
    val base = Project.extract(state).get(ThisBuild / baseDirectory)

    val buildProperties = base / "project" / "build.properties"

    val sbtVersionRegex = """sbt\.version\s*=\s*(.+)""".r

    if (!buildProperties.exists()) None
    else
      IO.readLines(buildProperties).collectFirst { case sbtVersionRegex(version) =>
        DependencyDiff.ResolvedDep("org.scala-sbt", "sbt", version.trim)
      }
  }

  private def generateSnapshot(state: State): Map[Group, Set[DependencyDiff.ResolvedDep]] = {
    val project = Project.extract(state)

    val snapshot = project.structure.allProjectRefs.flatMap { ref =>
      val group = Group(ref.project)

      Try(project.runTask(ref / Keys.allProjectDependencies, state)).toOption.toList.map { case (_, deps) =>
        group -> deps.map(DependencyDiff.ResolvedDep.fromModuleID).toSet
      }
    }.toMap

    snapshot.foreach { case (group, deps) =>
      state.log.info(s"Generated snapshot for `${group.name}` with ${deps.size} dependencies")
    }

    snapshot
  }

  def getVersionFinder(state: State, scalaVersion: String)(implicit
      logger: Logger,
      configCache: ConfigCache
  ): VersionFinder = {
    val project = Project.extract(state)

    val repositories: Seq[Repository] =
      project.get(ThisBuild / resolvers).collect { case repo: MavenRepo => MavenRepository(repo.root) }

    VersionFinder
      .fromCoursier(scalaVersion, project.get(ThisBuild / Keys.dependencyResolverTimeout), repositories)
      .cached
      .ignoringVersions(IgnoreFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateIgnores)))
      .excludingRetracted(RetractionFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateRetractions)))
      .pinningVersions(PinFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdatePins)))
  }

  private def withDependenciesFile(state: State, group: Group)(
      f: VersionFinder => MigrationFinder => RetractionFinder => (Extracted, DependenciesFile) => State
  ): State = {
    implicit val logger: Logger = state.log

    val project          = Project.extract(state)
    val base             = project.get(ThisBuild / baseDirectory)
    val file             = base / "project" / "dependencies.conf"
    val dependenciesFile = DependenciesFile(file)

    if (!file.exists() || !dependenciesFile.hasGroup(group)) state
    else {
      implicit val configCache: ConfigCache =
        ConfigCache(base / "target" / "sbt-dependencies" / "config-cache")

      val retractionFinder = RetractionFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateRetractions))

      val versionFinder = getVersionFinder(state, scalaVersion = "2.12.0")

      val migrationFinder = MigrationFinder.fromUrls(project.get(ThisBuild / Keys.dependencyMigrations))

      f(versionFinder)(migrationFinder)(retractionFinder)(project, dependenciesFile)
    }
  }

  /** Maps an SBT `CrossVersion` to the keyword used by the `cross-version` annotation. Returns `None` for unsupported
    * variants (e.g. `Constant`).
    */
  private def crossVersionKeyword(cv: CrossVersion): Option[String] =
    if (cv == CrossVersion.disabled) Some("disabled") // scalafix:ok
    else
      cv match {
        case _: CrossVersion.Binary => Some("binary")
        case _: CrossVersion.Full   => Some("full")
        case _: CrossVersion.Patch  => Some("patch")
        case _                      => None
      }

  /** Extracts a Java target version from a list of `javacOptions`, by scanning for `--release N`, `-release N`, or
    * `-target N` (a digit-only token following the flag). Returns `None` if no recognised flag is present.
    */
  private def javaVersionFromOptions(options: Seq[String]): Option[String] = {
    val flagRegex = """--?release|-target""".r

    options
      .sliding(2)
      .collectFirst {
        case Seq(flag, value) if flagRegex.pattern.matcher(flag).matches() && value.forall(_.isDigit) => value
      }
  }

  private def runStepsSafely(steps: String*)(state: State, outputDir: File): State = {
    implicit val logger: Logger = state.log

    IO.delete(outputDir / ".sbt-update-report")
    IO.delete(outputDir / ".sbt-dependency-snapshot")
    IO.delete(outputDir / ".sbt-dependency-diff")
    IO.delete(outputDir / ".sbt-build-snapshot")
    IO.delete(outputDir / ".sbt-common-snapshot")
    IO.delete(outputDir / ".sbt-plugin-snapshot")
    IO.delete(outputDir / ".sbt-version-snapshot")
    IO.delete(outputDir / ".sbt-post-update-hooks")

    val remaining = ListBuffer(steps: _*)

    var currentState = state // scalafix:ok

    while (remaining.nonEmpty) { // scalafix:ok
      val step = remaining.head

      remaining.remove(0)

      currentState = Try(Command.process(step, currentState))
        .flatMap(newState => Try(Project.extract(newState)).map(_ => newState))
        .onError { case e => logger.error(s"⚠ '$step' failed: ${e.getMessage}") }
        .onError { case _ if remaining.nonEmpty => logger.error(s"⚠ Skipped: ${remaining.mkString(", ")}") }
        .onError { case _ => writeUpdateReport(step, remaining.toList, outputDir) }
        .onError { case _ => remaining.clear() }
        .getOrElse(currentState)
    }

    currentState
  }

  private def writeUpdateReport(step: String, remaining: List[String], outputDir: File) = {
    val report =
      s"""> [!WARNING]
         |> `updateAllDependencies` failed at the `$step` step.
         |>
         |> Skipped steps: ${remaining.map(s => s"`$s`").mkString(", ")}.
         |>
         |> To fix this:
         |> 1. Clone this branch locally.
         |> 2. Fix the build so it compiles.
         |> 3. Run `sbt updateAllDependencies` to complete the remaining updates.
         |> 4. Push the changes to this branch.""".stripMargin

    IO.write(outputDir / ".sbt-update-report", report)
  }

}

object Commands extends Commands
