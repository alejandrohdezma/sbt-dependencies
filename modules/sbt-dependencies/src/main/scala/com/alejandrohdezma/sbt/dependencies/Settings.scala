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

import com.alejandrohdezma.sbt.dependencies.Eq._

class Settings {

  /** Whether the build is an SBT build. */
  val isSbtBuild: Def.Initialize[Boolean] = Def.setting {
    (ThisBuild / baseDirectory).value.name.equalsIgnoreCase("project")
  }

  /** The current group of the build. */
  val currentGroup: Def.Initialize[String] = Def.setting {
    if (isSbtBuild.value) "sbt-build" else name.value
  }

  /** The path to the dependencies.conf file. */
  val dependenciesFile: Def.Initialize[File] = Def.setting {
    if (isSbtBuild.value) (ThisBuild / baseDirectory).value / "dependencies.conf"
    else (ThisBuild / baseDirectory).value / "project" / "dependencies.conf"
  }

  /** The list of dependencies read from the file (with variables resolved). */
  val dependenciesFromFile: Def.Initialize[List[Dependency]] = Def.setting {
    implicit val logger: Logger = sLog.value

    implicit val versionFinder: VersionFinder = VersionFinder.fromCoursier(scalaBinaryVersion.value)

    val variableResolvers = Keys.dependencyVersionVariables.value

    DependenciesFile.read(dependenciesFile.value, currentGroup.value, variableResolvers)
  }

  /** Scala versions from the sbt-build group (only in normal build, not meta-build).
    *
    * Returns `Nil` when in the meta-build to avoid cyclic references with `crossScalaVersions`.
    */
  val buildScalaVersions: Def.Initialize[Seq[String]] = Def.setting {
    implicit val logger: Logger = sLog.value

    // Return Nil in meta-build to avoid cyclic reference with crossScalaVersions
    if (isSbtBuild.value) Nil
    else DependenciesFile.readScalaVersions(dependenciesFile.value, "sbt-build").map(_.toVersionString)
  }

  /** Scala versions from the current project's group (only in normal build, not meta-build).
    *
    * Returns `Nil` when in the meta-build to avoid cyclic references with `crossScalaVersions`.
    */
  val projectScalaVersions: Def.Initialize[Seq[String]] = Def.setting {
    implicit val logger: Logger = sLog.value

    // Return Nil in meta-build to avoid cyclic reference with crossScalaVersions
    if (isSbtBuild.value) Nil
    else DependenciesFile.readScalaVersions(dependenciesFile.value, currentGroup.value).map(_.toVersionString)
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

  /** The list of library dependencies to add to the project. */
  val libraryDependencies: Def.Initialize[Seq[ModuleID]] = Def.setting {
    val sbtV   = (pluginCrossBuild / sbtBinaryVersion).value
    val scalaV = (update / scalaBinaryVersion).value

    val dependencies =
      Keys.dependenciesFromFile.value
        .filter(_.matchesScalaVersion(scalaV))
        .map(_.toModuleID(sbtV, scalaV))

    lazy val self =
      sbtPluginExtra("com.alejandrohdezma" % "sbt-dependencies" % BuildInfo.version, sbtV, scalaV)

    // Add self when in meta-build so the plugin is available
    // in the build definition
    dependencies ++ (if (isSbtBuild.value) Seq(self) else Seq.empty)
  }

  /** Regex to match configuration transformations like `test->test`. */
  private val configRegex = """(.*)->(.*)""".r

}

object Settings extends Settings
