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

/** SBT commands for managing dependencies. */
class Commands {

  /** All commands provided by this plugin. */
  val all = Seq(updateAllDependencies, updateSbtDependenciesPlugin, updateBuildDependencies, installBuildDependencies)

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
          logger.info(s" ↳ ✅ $GREEN$current$RESET")
          line
        } else {
          logger.info(s" ↳ ⬆️  $YELLOW$current$RESET -> $CYAN$latest$RESET")
          s"""addSbtPlugin("com.alejandrohdezma" % "sbt-dependencies" % "$latest")"""
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
