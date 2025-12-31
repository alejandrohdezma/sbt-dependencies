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

import scala.Console._

import sbt.Keys._
import sbt._
import sbt.internal.util.complete.Parser

import com.alejandrohdezma.sbt.dependencies.Dependency.Version
import com.alejandrohdezma.sbt.dependencies.Eq._

/** SBT commands for managing dependencies. */
class Commands {

  /** All commands provided by this plugin. */
  val all = Seq(
    initDependenciesFile, updateAllDependencies, updateSbtDependenciesPlugin, updateBuildDependencies,
    installBuildDependencies
  )

  /** Creates (or recreates) the dependencies.yaml file based on current project dependencies. */
  lazy val initDependenciesFile = Command.command("initDependenciesFile") { state =>
    implicit val logger: Logger = state.log

    implicit val versionFinder: Utils.VersionFinder = (_, _, _, _) => List.empty

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

    val file =
      if (isSbtBuild) base / "dependencies.yaml"
      else base / "project" / "dependencies.yaml"

    // Keep existing dependencies from other groups, replace only the groups we're updating
    val existingDependencies =
      if (!file.exists()) Nil
      else DependenciesFile.read(file).filterNot(d => newGroups.contains(d.group))

    DependenciesFile.write(existingDependencies ++ newDependencies, file)

    if (isSbtBuild) {
      logger.info("📝 Created project/dependencies.yaml file with your dependencies")
      logger.info("💡 Remember to remove any `libraryDependencies +=` or `addSbtPlugin` settings from your build files")
      state
    } else {
      runCommand("reload plugins; initDependenciesFile; reload return")(state)
    }
  }

  /** Updates all dependencies: plugin, build dependencies, and project dependencies. */
  lazy val updateAllDependencies = Command.command("updateAllDependencies") { state =>
    runCommand("updateSbtDependenciesPlugin; updateBuildDependencies; updateDependencies; reload")(state)
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
      case line @ pluginRegex(Version(current)) =>
        val latest =
          Utils.findLatestVersion(
            organization = "com.alejandrohdezma",
            name = "sbt-dependencies",
            isCross = false,
            isSbtPlugin = true
          ) { candidate =>
            current.isValidCandidate(candidate)
          }

        if (latest.isSameVersion(current)) {
          logger.info(s" ↳ ✅ $GREEN${current.show}$RESET")
          line
        } else {
          logger.info(s" ↳ ⬆️  $YELLOW${current.show}$RESET -> $CYAN${latest.show}$RESET")
          s"""addSbtPlugin("com.alejandrohdezma" % "sbt-dependencies" % "${latest.show}")"""
        }
      case line => line
    }

    IO.writeLines(pluginsSbt, updatedLines)

    runCommand("reload")(state)
  }

  /** Updates dependencies in the meta-build (project/dependencies). */
  lazy val updateBuildDependencies = Command.command("updateBuildDependencies") { state =>
    runCommand("reload plugins; updateDependencies; reload return")(state)
  }

  /** Installs a dependency in the meta-build (project/dependencies). */
  lazy val installBuildDependencies = Command.single("installBuildDependencies") { case (state, dependency) =>
    runCommand(s"reload plugins; install $dependency; reload return")(state)
  }

  private def runCommand(command: String)(state: State): State = {
    implicit val logger: Logger = state.log

    Parser.parse(command, state.combinedParser) match {
      case Right(cmd) => cmd()
      case Left(err)  => Utils.fail(s"Failed to parse command: $err")
    }
  }

}

object Commands extends Commands
