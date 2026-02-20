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
import sbt.{Keys => _, _}

import com.alejandrohdezma.sbt.dependencies.constraints.ConfigCache
import com.alejandrohdezma.sbt.dependencies.finders.IgnoreFinder
import com.alejandrohdezma.sbt.dependencies.finders.MigrationFinder
import com.alejandrohdezma.sbt.dependencies.finders.PinFinder
import com.alejandrohdezma.sbt.dependencies.finders.RetractionFinder
import com.alejandrohdezma.sbt.dependencies.finders.Utils
import com.alejandrohdezma.sbt.dependencies.finders.VersionFinder
import com.alejandrohdezma.sbt.dependencies.io.DependenciesFile
import com.alejandrohdezma.sbt.dependencies.io.DependencyDiff
import com.alejandrohdezma.sbt.dependencies.io.Scalafmt
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.model.Eq._

/** SBT commands for managing dependencies. */
class Commands {

  /** All commands provided by this plugin. */
  val all = Seq(initDependenciesFile, updateAllDependencies, updateSbtPlugin, updateBuildDependencies,
    installBuildDependencies, updateSbt, updateBuildScalaVersions, updateScalafmtVersion, disableEvictionWarnings,
    enableEvictionWarnings, snapshotDependencies, snapshotBuildDependencies, computeDependencyDiff)

  /** Creates (or recreates) the dependencies.conf file based on current project dependencies and Scala versions.
    *
    * Accepts optional flags:
    *   - `--scala-versions` — include scala versions in the output
    *   - `--compiler-plugins` — include compiler plugin dependencies
    *   - `--all` — enables both of the above
    */
  lazy val initDependenciesFile = Command.args("initDependenciesFile", "<options>") { (state, args) =>
    implicit val logger: Logger = state.log

    val includeAll             = args.contains("--all")
    val includeScalaVersions   = includeAll || args.contains("--scala-versions")
    val includeCompilerPlugins = includeAll || args.contains("--compiler-plugins")

    val project = Project.extract(state)

    val base = project.get(ThisBuild / baseDirectory)

    val isSbtBuild = base.name.equalsIgnoreCase("project")

    val pluginOrg  = project.get(Keys.sbtDependenciesPluginOrganization)
    val pluginName = project.get(Keys.sbtDependenciesPluginName)

    val newGroups = project.structure.allProjectRefs.map { ref =>
      if (isSbtBuild) `sbt-build` else project.get(ref / name)
    }.toSet

    val dependenciesByGroup: Map[String, List[Dependency]] =
      project.structure.allProjectRefs.flatMap { ref =>
        val group = if (isSbtBuild) `sbt-build` else project.get(ref / name)

        project
          .get(ref / libraryDependencies)
          .flatMap(Dependency.fromModuleID(_).toList)
          .filter(dep => includeCompilerPlugins || !dep.configuration.contains("plugin->default(compile)"))
          .map(group -> _)
      }.groupBy(_._1)
        .mapValues(_.map(_._2).toList)
        .mapValues(_.filterNot(_.organization === "org.scala-lang"))
        .mapValues(_.filterNot(dep => dep.organization === pluginOrg && dep.name === pluginName))

    // Gather Scala versions for each group (skip in meta-build, always 2.12)
    val scalaVersionsByGroup: Map[String, List[String]] =
      if (!includeScalaVersions || isSbtBuild) Map.empty
      else
        project.structure.allProjectRefs.map { ref =>
          project.get(ref / name) -> project.get(ref / crossScalaVersions).toList
        }.toMap

    // Check if all projects share the same Scala versions
    val uniqueVersionSets = scalaVersionsByGroup.values.map(_.sorted).toSet
    val sharedVersions    = if (uniqueVersionSets.size === 1) uniqueVersionSets.head else Nil

    val file =
      if (isSbtBuild) base / "dependencies.conf"
      else base / "project" / "dependencies.conf"

    // Write sbt-build group with shared scala versions (if any)
    val sbtBuildDeps = dependenciesByGroup.getOrElse(`sbt-build`, Nil)

    if (sharedVersions.nonEmpty || (newGroups.contains(`sbt-build`) && sbtBuildDeps.nonEmpty)) {
      DependenciesFile.write(file, `sbt-build`, sbtBuildDeps, sharedVersions)
    }

    // Write each group's dependencies (and scala versions if not shared)
    newGroups.filterNot(_ === `sbt-build`).foreach { group =>
      val deps          = dependenciesByGroup.getOrElse(group, Nil)
      val scalaVersions = if (sharedVersions.isEmpty) scalaVersionsByGroup.getOrElse(group, Nil) else Nil
      DependenciesFile.write(file, group, deps, scalaVersions)
    }

    logger.info("✎ Created project/dependencies.conf file with your dependencies")
    logger.info("ℹ Remember to remove any `libraryDependencies +=` or `addSbtPlugin` settings from your build files")

    val flagsString = args.mkString(" ")

    if (isSbtBuild) state
    else if (!isPluginInMetaBuild(state)) state
    else queueInMetaBuild(s"initDependenciesFile $flagsString")(state)
  }

  /** Updates everything: plugin, Scala versions, dependencies, scalafmt, and SBT version. */
  lazy val updateAllDependencies = Command.command("updateAllDependencies") { state =>
    val base = Project.extract(state).get(ThisBuild / baseDirectory)

    val steps = List(
      "disableEvictionWarnings",   // Lower eviction errors so snapshots can resolve
      "snapshotDependencies",      // Record resolved main-build deps before changes
      "snapshotBuildDependencies", // Record declared meta-build deps before changes
      "updateSbtPlugin",           // Update sbt-dependencies (or wrapper) plugin version
      "updateBuildScalaVersions",  // Update Scala versions in project/dependencies.conf
      "updateBuildDependencies",   // Update dependencies in project/dependencies.conf
      "updateScalafmtVersion",     // Update scalafmt version in .scalafmt.conf files
      "updateScalaVersions",       // Update Scala versions in main build
      "updateDependencies",        // Update dependencies in main build
      "reload",                    // Reload build (resets session settings)
      "updateSbt",                 // Update sbt version in build.properties
      "disableEvictionWarnings",   // Re-lower eviction errors (lost after reload)
      "computeDependencyDiff",     // Compute main diff and merge sbt-build diff
      "enableEvictionWarnings"     // Restore eviction errors
    )

    runStepsSafely(steps: _*)(state, base / "target" / "sbt-dependencies")
  }

  /** Updates the configured SBT plugin. Checks `project/project/plugins.sbt` first, falling back to
    * `project/plugins.sbt`.
    */
  lazy val updateSbtPlugin = Command.command("updateSbtPlugin") { state =>
    implicit val logger: Logger = state.log

    val project = Project.extract(state)

    ConfigCache.withCacheDir(project.get(ThisBuild / baseDirectory) / "target" / "sbt-dependencies" / "config-cache")

    val ignoreFinder = IgnoreFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateIgnores))

    val retractionFinder = RetractionFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateRetractions))

    val pinFinder = PinFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdatePins))

    implicit val versionFinder: VersionFinder =
      VersionFinder
        .fromCoursier("not-relevant", project.get(ThisBuild / Keys.dependencyResolverTimeout))
        .cached
        .ignoringVersions(ignoreFinder)
        .excludingRetracted(retractionFinder)
        .pinningVersions(pinFinder)

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

        if (updatedLines.exists(_._2)) Some(Command.process("reload", state))
        else Some(state)
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
    * Reads the file directly from the main build (no `reload plugins` needed), resolves each dependency to its latest
    * version using Coursier, and writes the updated versions back. Retracted versions are warned about but not applied.
    */
  lazy val updateBuildDependencies = Command.command("updateBuildDependencies") { state =>
    withSbtBuild(state) { implicit versionFinder => implicit migrationFinder => retractionFinder => (project, file) =>
      implicit val logger: Logger = state.log

      val deps = DependenciesFile.read(file, `sbt-build`, Map.empty)

      if (deps.nonEmpty) {
        logger.info(s"\n↻ Updating dependencies for `${`sbt-build`}` in project/dependencies.conf\n")

        val updated = Utils.resolveLatestVersions(deps, project.get(ThisBuild / Keys.dependencyResolverParallelism))

        updated.foreach(retractionFinder.warnIfRetracted(_))

        DependenciesFile.write(file, `sbt-build`, updated)
      }

      state
    }
  }

  /** Resolves latest Scala patch versions in the `sbt-build` group of `project/dependencies.conf`.
    *
    * Reads the file directly from the main build (no `reload plugins` needed) and updates each Scala version to the
    * latest patch release within the same minor series (e.g. 2.12.x, 3.3.x).
    */
  lazy val updateBuildScalaVersions = Command.command("updateBuildScalaVersions") { state =>
    withSbtBuild(state) { implicit versionFinder => implicit migrationFinder => _ => (_, file) =>
      implicit val logger: Logger = state.log

      val versions = DependenciesFile.readScalaVersions(file, `sbt-build`)

      if (versions.nonEmpty) {
        logger.info(s"\n↻ Updating Scala versions for `${`sbt-build`}` in project/dependencies.conf\n")

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

        DependenciesFile.writeScalaVersions(file, `sbt-build`, updated)
      }

      state
    }
  }

  /** Installs a dependency in the `sbt-build` group of `project/dependencies.conf` from the main build.
    *
    * Accepts a dependency string with or without a version (e.g. `org::name:1.0.0` or `org::name`). When the version is
    * omitted, the latest stable version is resolved automatically.
    */
  lazy val installBuildDependencies = Command.single("installBuildDependencies") { case (state, dependency) =>
    implicit val logger: Logger = state.log

    withSbtBuild(state) { implicit versionFinder => _ => _ => (_, file) =>
      val dependencies = DependenciesFile.read(file, `sbt-build`, Map.empty)

      val dep = Dependency.parseIncludingMissingVersion(dependency)

      logger.info(s"➕ [${`sbt-build`}] $YELLOW${dep.toLine}$RESET")

      DependenciesFile.write(file, `sbt-build`, dependencies.filterNot(_.isSameArtifact(dep)) :+ dep)

      state
    }
  }

  /** Updates scalafmt version in `.scalafmt.conf` to the latest version. */
  lazy val updateScalafmtVersion = Command.command("updateScalafmtVersion") { state =>
    implicit val logger: Logger = state.log

    val project = Project.extract(state)

    val base = project.get(ThisBuild / baseDirectory)

    ConfigCache.withCacheDir(base / "target" / "sbt-dependencies" / "config-cache")

    logger.info("\n↻ Checking for new versions of Scalafmt\n")

    val ignoreFinder = IgnoreFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateIgnores))

    implicit val retractionFinder = RetractionFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateRetractions))

    val pinFinder = PinFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdatePins))

    implicit val versionFinder: VersionFinder =
      VersionFinder
        .fromCoursier("2.13", project.get(ThisBuild / Keys.dependencyResolverTimeout))
        .cached
        .ignoringVersions(ignoreFinder)
        .excludingRetracted(retractionFinder)
        .pinningVersions(pinFinder)

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

    ConfigCache.withCacheDir(base / "target" / "sbt-dependencies" / "config-cache")

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

        val ignoreFinder = IgnoreFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateIgnores))

        val retractionFinder = RetractionFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateRetractions))

        val pinFinder = PinFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdatePins))

        implicit val versionFinder: VersionFinder =
          VersionFinder
            .fromCoursier("not-relevant", project.get(ThisBuild / Keys.dependencyResolverTimeout))
            .cached
            .ignoringVersions(ignoreFinder)
            .excludingRetracted(retractionFinder)
            .pinningVersions(pinFinder)

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
  lazy val snapshotBuildDependencies = Command.command("snapshotBuildDependencies") { state =>
    Try {
      withSbtBuild(state) { _ => _ => _ => (project, file) =>
        implicit val logger: Logger = state.log

        val dependencies = DependenciesFile.read(file, `sbt-build`, Map.empty)

        if (dependencies.nonEmpty) {
          val snapshot = Map(
            `sbt-build` -> dependencies
              .map(d => DependencyDiff.ResolvedDep(d.organization, d.name, d.version.toVersionString))
              .toSet
          )

          val outputFile =
            project.get(ThisBuild / baseDirectory) / "target" / "sbt-dependencies" / ".sbt-build-snapshot"

          DependencyDiff.writeSnapshot(outputFile, snapshot)
        }

        state
      }
    }.onError { case e =>
      state.log.trace(e)
      state.log.error("Unable to generate build dependency snapshot")
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

      if (!snapshotFile.exists()) {
        state.log.warn("Snapshot file not found, skipping diff computation")
      } else {

        // Merge sbt-build snapshot from project/dependencies.conf
        val buildSnapshotFile = outputDir / ".sbt-build-snapshot"

        val (buildBefore, buildAfter) =
          if (!buildSnapshotFile.exists())
            (Set.empty[DependencyDiff.ResolvedDep], Set.empty[DependencyDiff.ResolvedDep])
          else {
            val file = base / "project" / "dependencies.conf"

            val before = DependencyDiff.readSnapshot(buildSnapshotFile).getOrElse(`sbt-build`, Set.empty)

            val after = {
              DependenciesFile
                .read(file, `sbt-build`, Map.empty)
                .map(d => DependencyDiff.ResolvedDep(d.organization, d.name, d.version.toVersionString))
                .toSet
            }

            IO.delete(buildSnapshotFile)

            (before, after)
          }

        val before = DependencyDiff.readSnapshot(snapshotFile)
        val after  = generateSnapshot(state)

        val diffs = DependencyDiff.compute(before + (`sbt-build` -> buildBefore), after + (`sbt-build` -> buildAfter))

        if (diffs.nonEmpty)
          IO.write(outputDir / ".sbt-dependency-diff", DependencyDiff.toHocon(diffs))

        IO.delete(snapshotFile)
      }
    }.onError { case e => state.log.warn(s"computeDependencyDiff: ${e.getMessage}") }

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

  private def generateSnapshot(state: State): Map[String, Set[DependencyDiff.ResolvedDep]] = {
    val project = Project.extract(state)

    val snapshot = project.structure.allProjectRefs.flatMap { ref =>
      val projectId = ref.project

      Try(project.runTask(ref / Keys.allProjectDependencies, state)).toOption.toList.map { case (_, deps) =>
        projectId -> deps.map(DependencyDiff.ResolvedDep.fromModuleID).toSet
      }
    }.toMap

    snapshot.foreach { case (proj, deps) =>
      state.log.info(s"Generated snapshot for `$proj` with ${deps.size} dependencies")
    }

    snapshot
  }

  private def withSbtBuild(state: State)(
      f: VersionFinder => MigrationFinder => RetractionFinder => (Extracted, File) => State
  ): State = {
    implicit val logger: Logger = state.log

    val project = Project.extract(state)
    val base    = project.get(ThisBuild / baseDirectory)
    val file    = base / "project" / "dependencies.conf"

    if (!file.exists() || !DependenciesFile.hasGroup(file, `sbt-build`)) state
    else {
      ConfigCache.withCacheDir(base / "target" / "sbt-dependencies" / "config-cache")

      val retractionFinder = RetractionFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateRetractions))

      val versionFinder = VersionFinder
        .fromCoursier("2.12", project.get(ThisBuild / Keys.dependencyResolverTimeout))
        .cached
        .ignoringVersions(IgnoreFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateIgnores)))
        .excludingRetracted(retractionFinder)
        .pinningVersions(PinFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdatePins)))

      val migrationFinder = MigrationFinder.fromUrls(project.get(ThisBuild / Keys.dependencyMigrations))

      f(versionFinder)(migrationFinder)(retractionFinder)(project, file)
    }
  }

  val `sbt-build` = "sbt-build"

  private def queueInMetaBuild(commands: String*)(state: State): State = {
    val composite = ("reload plugins" +: commands :+ "reload return").mkString("; ")
    state.copy(remainingCommands = Exec(composite, None) +: state.remainingCommands)
  }

  private def runStepsSafely(steps: String*)(state: State, outputDir: File): State = {
    implicit val logger: Logger = state.log

    IO.delete(outputDir / ".sbt-update-report")
    IO.delete(outputDir / ".sbt-dependency-snapshot")
    IO.delete(outputDir / ".sbt-dependency-diff")
    IO.delete(outputDir / ".sbt-build-snapshot")

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
