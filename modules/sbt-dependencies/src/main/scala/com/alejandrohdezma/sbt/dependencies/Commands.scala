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

import sbt.Keys._
import sbt._
import sbt.internal.util.complete.Parser

import com.alejandrohdezma.sbt.dependencies.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.Eq._

/** SBT commands for managing dependencies. */
class Commands {

  /** All commands provided by this plugin. */
  val all = Seq(
    initDependenciesFile, updateAllDependencies, updateSbtDependenciesPlugin, updateBuildDependencies,
    installBuildDependencies, updateSbt, updateBuildScalaVersions, updateScalafmtVersion
  )

  /** Creates (or recreates) the dependencies.yaml file based on current project dependencies and Scala versions. */
  lazy val initDependenciesFile = Command.command("initDependenciesFile") { state =>
    implicit val logger: Logger = state.log

    val project = Project.extract(state)

    val base = project.get(ThisBuild / baseDirectory)

    val isSbtBuild = base.name.equalsIgnoreCase("project")

    val newGroups = project.structure.allProjectRefs.map { ref =>
      if (isSbtBuild) "sbt-build" else project.get(ref / name)
    }.toSet

    val newDependencies = project.structure.allProjectRefs.flatMap { ref =>
      val group = if (isSbtBuild) "sbt-build" else project.get(ref / name)

      project
        .get(ref / libraryDependencies)
        .flatMap(Dependency.fromModuleID(_, group).toList)
    }.toList
      .filterNot(d => d.organization === "com.alejandrohdezma" && d.name === "sbt-dependencies")

    // Gather Scala versions for each group (skip in meta-build, always 2.12)
    val scalaVersionsByGroup: Map[String, List[String]] =
      if (isSbtBuild) Map.empty
      else
        project.structure.allProjectRefs.map { ref =>
          project.get(ref / name) -> project.get(ref / crossScalaVersions).toList
        }.toMap

    // Check if all projects share the same Scala versions
    val uniqueVersionSets = scalaVersionsByGroup.values.map(_.sorted).toSet
    val sharedVersions    = if (uniqueVersionSets.size === 1) uniqueVersionSets.head else Nil

    val file =
      if (isSbtBuild) base / "dependencies.yaml"
      else base / "project" / "dependencies.yaml"

    // Write sbt-build group with shared scala versions (if any)
    if (sharedVersions.nonEmpty || newGroups.contains("sbt-build")) {
      val deps = newDependencies.filter(_.group === "sbt-build")
      DependenciesFile.write(file, "sbt-build", deps, sharedVersions)
    }

    // Write each group's dependencies (and scala versions if not shared)
    newGroups.filterNot(_ === "sbt-build").foreach { group =>
      val deps          = newDependencies.filter(_.group === group)
      val scalaVersions = if (sharedVersions.isEmpty) scalaVersionsByGroup.getOrElse(group, Nil) else Nil
      DependenciesFile.write(file, group, deps, scalaVersions)
    }

    if (isSbtBuild) {
      logger.info("📝 Created project/dependencies.yaml file with your dependencies")
      logger.info("💡 Remember to remove any `libraryDependencies +=` or `addSbtPlugin` settings from your build files")
      state
    } else {
      runCommand("reload plugins", "initDependenciesFile", "reload return")(state)
    }
  }

  /** Updates everything: plugin, Scala versions, dependencies, scalafmt, and SBT version. */
  lazy val updateAllDependencies = Command.command("updateAllDependencies") { state =>
    runCommand(
      "updateSbtDependenciesPlugin", "updateBuildScalaVersions", "updateBuildDependencies", "updateScalafmtVersion",
      "updateScalaVersions", "updateDependencies", "reload", "updateSbt"
    )(state)
  }

  /** Updates the sbt-dependencies plugin itself in `project/project/plugins.sbt`. */
  lazy val updateSbtDependenciesPlugin = Command.command("updateSbtDependenciesPlugin") { state =>
    implicit val logger: Logger = state.log

    val base = Project.extract(state).get(ThisBuild / baseDirectory)

    val pluginsSbt = base / "project" / "project" / "plugins.sbt"

    if (!pluginsSbt.exists()) {
      logger.warn("It is recommended to add the `sbt-dependencies` plugin to the `project/project/plugins.sbt` file")
      state
    }

    val lines = IO.readLines(pluginsSbt)

    // Regex to match addSbtPlugin line for this plugin (handles whitespace variations)
    val pluginRegex =
      """addSbtPlugin\s*\(\s*"com\.alejandrohdezma"\s*%\s*"sbt-dependencies"\s*%\s*"([^"]+)"\s*\).*""".r

    if (!lines.exists(pluginRegex.findFirstIn(_).isDefined)) {
      logger.warn("It is recommended to add the `sbt-dependencies` plugin to the `project/project/plugins.sbt` file")
      state
    }

    logger.info("\n🔄 Checking for new versions of the `sbt-dependencies` plugin\n")

    implicit val versionFinder: Utils.VersionFinder = Utils.VersionFinder.fromCoursier("not-relevant")

    val updatedLines = lines.map {
      case line @ pluginRegex(Numeric(current)) =>
        val latest =
          Utils.findLatestVersion("com.alejandrohdezma", "sbt-dependencies", isCross = false, isSbtPlugin = true,
            current)

        if (latest.isSameVersion(current)) {
          logger.info(s" ↳ ✅ $GREEN${current.show}$RESET")
          line
        } else {
          logger.info(s" ↳ ⬆️ $YELLOW${current.show}$RESET -> $CYAN${latest.show}$RESET")
          s"""addSbtPlugin("com.alejandrohdezma" % "sbt-dependencies" % "${latest.show}")"""
        }
      case line => line
    }

    IO.writeLines(pluginsSbt, updatedLines)

    runCommand("reload")(state)
  }

  /** Updates dependencies in the meta-build (project/dependencies). */
  lazy val updateBuildDependencies = Command.command("updateBuildDependencies") { state =>
    runCommand("reload plugins", "updateDependencies", "reload return")(state)
  }

  /** Updates Scala versions in the meta-build (project/dependencies). */
  lazy val updateBuildScalaVersions = Command.command("updateBuildScalaVersions") { state =>
    runCommand("reload plugins", "updateScalaVersions", "reload return")(state)
  }

  /** Installs a dependency in the meta-build (project/dependencies). */
  lazy val installBuildDependencies = Command.single("installBuildDependencies") { case (state, dependency) =>
    runCommand("reload plugins", s"install $dependency", "reload return")(state)
  }

  /** Updates scalafmt version in `.scalafmt.conf` to the latest version. */
  lazy val updateScalafmtVersion = Command.command("updateScalafmtVersion") { state =>
    implicit val logger: Logger = state.log

    val base = Project.extract(state).get(ThisBuild / baseDirectory)

    logger.info("\n🔄 Checking for new versions of Scalafmt\n")

    implicit val versionFinder: Utils.VersionFinder = Utils.VersionFinder.fromCoursier("2.13")

    Scalafmt.updateVersion(base)

    state
  }

  /** Updates SBT version in `project/build.properties` to the latest version. */
  lazy val updateSbt = Command.command("updateSbt") { state =>
    implicit val logger: Logger = state.log

    val base = Project.extract(state).get(ThisBuild / baseDirectory)

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
        logger.info("\n🔄 Checking for new versions of SBT\n")

        implicit val versionFinder: Utils.VersionFinder = Utils.VersionFinder.fromCoursier("not-relevant")

        val updatedLines = lines.map {
          case line @ sbtVersionRegex(Numeric(current)) =>
            val latest = Utils.findLatestVersion("org.scala-sbt", "sbt", isCross = false, isSbtPlugin = false, current)

            if (latest.isSameVersion(current)) {
              logger.info(s" ↳ ✅ $GREEN${current.show}$RESET")
              (line, false)
            } else {
              logger.info(s" ↳ ⬆️ $YELLOW${current.show}$RESET -> $CYAN${latest.show}$RESET")
              (s"sbt.version=${latest.toVersionString}", true)
            }
          case line => (line, false)
        }

        IO.writeLines(buildProperties, updatedLines.map(_._1))

        if (updatedLines.exists(_._2)) runCommand("reboot")(state)
        else state
      }
    }
  }

  private def runCommand(commands: String*)(state: State): State = {
    implicit val logger: Logger = state.log

    Parser.parse(commands.mkString("; "), state.combinedParser) match {
      case Right(cmd) => cmd()
      case Left(err)  => Utils.fail(s"Failed to parse command: $err")
    }
  }

}

object Commands extends Commands
