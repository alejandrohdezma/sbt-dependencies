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

import com.alejandrohdezma.sbt.dependencies.io.DependenciesFile
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
    implicit val logger: Logger = sLog.value

    if (isSbtBuild.value) Nil
    else dependenciesFile.value.readScalaVersions(`common-settings`).map(_.toVersionString)
  }

  /** Scala versions from the current project's group (only in normal build, not meta-build).
    *
    * Returns `Nil` when in the meta-build for the same reason as [[commonScalaVersions]].
    */
  val projectScalaVersions: Def.Initialize[Seq[String]] = Def.setting {
    implicit val logger: Logger = sLog.value

    if (isSbtBuild.value) Nil
    else dependenciesFile.value.readScalaVersions(currentGroup.value).map(_.toVersionString)
  }

  /** Java target version from the `common-settings` group, used as a default for every non-meta project. */
  val commonJavaVersion: Def.Initialize[Option[String]] = Def.setting {
    implicit val logger: Logger = sLog.value

    if (isSbtBuild.value) None
    else dependenciesFile.value.readJavaVersion(`common-settings`)
  }

  /** Java target version from the current project's group. */
  val projectJavaVersion: Def.Initialize[Option[String]] = Def.setting {
    implicit val logger: Logger = sLog.value

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
    * In the meta-build, only the project group is read — `common-settings.dependencies` are not for plugins.
    */
  val libraryDependencies: Def.Initialize[Seq[ModuleID]] = Def.setting {
    val sbtV                    = (pluginCrossBuild / sbtBinaryVersion).value
    val scalaV                  = (update / scalaBinaryVersion).value
    implicit val logger: Logger = sLog.value

    val variableResolvers = Keys.dependencyVersionVariables.value
    val file              = dependenciesFile.value

    def readGroup(group: Group): Seq[ModuleID] =
      file
        .read(group, variableResolvers)
        .filter(_.matchesScalaVersion(scalaV))
        .filter(dep => dep.scalaFilter.forall(scalaV.startsWith))
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

  /** Regex to match configuration transformations like `test->test`. */
  private val configRegex = """(.*)->(.*)""".r

}

object Settings extends Settings
