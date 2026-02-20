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
    enableEvictionWarnings, snapshotDependencies, snapshotBuildDependencies, computeDependencyDiff,
    computeBuildDependencyDiff)

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
      if (isSbtBuild) "sbt-build" else project.get(ref / name)
    }.toSet

    val dependenciesByGroup: Map[String, List[Dependency]] =
      project.structure.allProjectRefs.flatMap { ref =>
        val group = if (isSbtBuild) "sbt-build" else project.get(ref / name)

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
    val sbtBuildDeps = dependenciesByGroup.getOrElse("sbt-build", Nil)

    if (sharedVersions.nonEmpty || (newGroups.contains("sbt-build") && sbtBuildDeps.nonEmpty)) {
      DependenciesFile.write(file, "sbt-build", sbtBuildDeps, sharedVersions)
    }

    // Write each group's dependencies (and scala versions if not shared)
    newGroups.filterNot(_ === "sbt-build").foreach { group =>
      val deps          = dependenciesByGroup.getOrElse(group, Nil)
      val scalaVersions = if (sharedVersions.isEmpty) scalaVersionsByGroup.getOrElse(group, Nil) else Nil
      DependenciesFile.write(file, group, deps, scalaVersions)
    }

    logger.info("✎ Created project/dependencies.conf file with your dependencies")
    logger.info("ℹ Remember to remove any `libraryDependencies +=` or `addSbtPlugin` settings from your build files")

    val flagsString = args.mkString(" ")

    if (isSbtBuild) state
    else runInMetaBuild(s"initDependenciesFile $flagsString")(state)
  }

  /** Updates everything: plugin, Scala versions, dependencies, scalafmt, and SBT version. */
  lazy val updateAllDependencies = Command.command("updateAllDependencies") { state =>
    val base = Project.extract(state).get(ThisBuild / baseDirectory)

    val steps = List(
      "snapshotDependencies",       // Record resolved deps before any changes
      "snapshotBuildDependencies",  // Record resolved meta-build deps before any changes
      "updateSbtPlugin",            // Update sbt-dependencies (or wrapper) plugin version
      "updateBuildScalaVersions",   // Update Scala versions in meta-build
      "updateBuildDependencies",    // Update dependencies in meta-build
      "computeBuildDependencyDiff", // Compute meta-build dependency diff from snapshot
      "updateScalafmtVersion",      // Update scalafmt version in .scalafmt.conf files
      "updateScalaVersions",        // Update Scala versions in main build
      "updateDependencies",         // Update dependencies in main build
      "reload",                     // Reload build with updated dependencies
      "updateSbt",                  // Update sbt version in build.properties
      "disableEvictionWarnings",    // Temporarily lower eviction errors to info
      "computeDependencyDiff",      // Compute main dependency diff and merge meta-build diff
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

  /** Updates dependencies in the meta-build (project/dependencies). */
  lazy val updateBuildDependencies = Command.command("updateBuildDependencies") { state =>
    runInMetaBuild("updateDependencies")(state)
  }

  /** Updates Scala versions in the meta-build (project/dependencies). */
  lazy val updateBuildScalaVersions = Command.command("updateBuildScalaVersions") { state =>
    runInMetaBuild("updateScalaVersions")(state)
  }

  /** Installs a dependency in the meta-build (project/dependencies). */
  lazy val installBuildDependencies = Command.single("installBuildDependencies") { case (state, dependency) =>
    runInMetaBuild(s"install $dependency")(state)
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
              (s"sbt.version=${latest.toVersionString}", true)
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

  /** Snapshots all resolved dependencies in the meta-build (project/) for later diff computation. */
  lazy val snapshotBuildDependencies = Command.command("snapshotBuildDependencies") { state =>
    Try(runInMetaBuild("snapshotDependencies")(state)).onError { case e =>
      state.log.trace(e)
      state.log.error("Unable to generate build dependency snapshot")
    }

    state
  }

  /** Computes dependency diff from snapshot, writes `target/sbt-dependencies/.sbt-dependency-diff`, and cleans up the
    * snapshot file.
    */
  lazy val computeDependencyDiff = Command.command("computeDependencyDiff") { state =>
    Try {
      val base = Project.extract(state).get(ThisBuild / baseDirectory)

      val outputDir = base / "target" / "sbt-dependencies"

      val snapshotFile = outputDir / ".sbt-dependency-snapshot"

      if (!snapshotFile.exists()) {
        state.log.warn("Snapshot file not found, skipping diff computation")
      } else {
        val before = DependencyDiff.readSnapshot(snapshotFile)

        val after = generateSnapshot(state)

        var diffs = DependencyDiff.compute(before, after) // scalafix:ok

        // Merge meta-build diff (produced by computeBuildDependencyDiff) under "sbt-build" key
        val buildDiffFile = base / "project" / "target" / "sbt-dependencies" / ".sbt-dependency-diff"

        if (buildDiffFile.exists()) {
          val buildDiffs = DependencyDiff.readDiff(buildDiffFile)

          if (buildDiffs.nonEmpty) {
            val merged = DependencyDiff.ProjectDiff(
              updated = buildDiffs.values.flatMap(_.updated).toList,
              added = buildDiffs.values.flatMap(_.added).toList,
              removed = buildDiffs.values.flatMap(_.removed).toList
            )

            diffs = diffs + ("sbt-build" -> merged)
          }

          IO.delete(buildDiffFile)
        }

        if (diffs.nonEmpty)
          IO.write(outputDir / ".sbt-dependency-diff", DependencyDiff.toHocon(diffs))

        IO.delete(snapshotFile)
      }
    }.onError { case e => state.log.warn(s"computeDependencyDiff: ${e.getMessage}") }

    state
  }

  /** Computes dependency diff for the meta-build (project/) and writes it to
    * `project/target/sbt-dependencies/.sbt-dependency-diff`.
    */
  lazy val computeBuildDependencyDiff = Command.command("computeBuildDependencyDiff") { state =>
    Try(runInMetaBuild("computeDependencyDiff")(state)).onError { case e =>
      state.log.trace(e)
      state.log.error("Unable to compute build dependency diff")
    }

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

  private def runInMetaBuild(commands: String*)(state: State): State =
    if (isPluginInMetaBuild(state))
      Command.process(("reload plugins" +: commands :+ "reload return").mkString("; "), state)
    else state

  private def runStepsSafely(steps: String*)(state: State, outputDir: File): State = {
    implicit val logger: Logger = state.log

    IO.delete(outputDir / ".sbt-update-report")
    IO.delete(outputDir / ".sbt-dependency-snapshot")
    IO.delete(outputDir / ".sbt-dependency-diff")

    val buildOutputDir = outputDir.getParentFile.getParentFile / "project" / "target" / "sbt-dependencies"

    IO.delete(buildOutputDir / ".sbt-dependency-snapshot")
    IO.delete(buildOutputDir / ".sbt-dependency-diff")

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
