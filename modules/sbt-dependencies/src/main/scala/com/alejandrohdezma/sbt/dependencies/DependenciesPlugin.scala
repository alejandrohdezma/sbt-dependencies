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

import java.lang.Runtime

import sbt.Keys._
import sbt._

import com.alejandrohdezma.sbt.dependencies.constraints.ArtifactMigration
import com.alejandrohdezma.sbt.dependencies.constraints.PostUpdateHook
import com.alejandrohdezma.sbt.dependencies.constraints.RetractedArtifact
import com.alejandrohdezma.sbt.dependencies.constraints.ScalafixMigration
import com.alejandrohdezma.sbt.dependencies.constraints.UpdateIgnore
import com.alejandrohdezma.sbt.dependencies.constraints.UpdatePin

/** SBT plugin for managing dependencies through a `project/dependencies` file.
  *
  * This plugin automatically populates `libraryDependencies` based on the dependencies file and provides commands/tasks
  * for updating and installing dependencies.
  */
object DependenciesPlugin extends AutoPlugin {

  override def trigger = allRequirements

  /** Keys exported by this plugin (dependenciesFromFile, updateDependencies, install). */
  object autoImport extends Keys

  import autoImport._

  /** Tag for ensuring exclusive execution of dependency operations. */
  private val Exclusive = Tags.Tag("dependencies-exclusive")

  /** Global settings: reads dependencies file and registers commands. */
  override def globalSettings: Seq[Def.Setting[_]] = Seq(
    concurrentRestrictions += Tags.limit(Exclusive, 1),
    commands              ++= Commands.all
  )

  override def buildSettings: Seq[Setting[_]] = Seq(
    dependencyVersionVariables        := Map.empty,
    sbtDependenciesPluginOrganization := "com.alejandrohdezma",
    sbtDependenciesPluginName         := "sbt-dependencies",
    dependencyMigrations              := ArtifactMigration.default,
    dependencyUpdateRetractions       := RetractedArtifact.default,
    dependencyUpdateIgnores           := UpdateIgnore.default,
    dependencyUpdatePins              := UpdatePin.default,
    dependencyPostUpdateHooks         := PostUpdateHook.default,
    dependencyScalafixMigrations      := ScalafixMigration.default,
    dependencyResolverTimeout         := 60,
    dependencyResolverParallelism     := Runtime.getRuntime.availableProcessors,
    dependenciesManagedScalaVersions  := Settings.buildScalaVersions.value.nonEmpty,
    scalaVersion := Def.settingDyn {
      val file = Settings.dependenciesFile.value
      if (file.exists()) Def.setting {
        val versions = Settings.buildScalaVersions.value
        if (versions.nonEmpty) versions.head else scalaVersion.value
      }
      else Def.setting(scalaVersion.value)
    }.value,
    crossScalaVersions := Def.settingDyn {
      val file = Settings.dependenciesFile.value
      if (file.exists()) Def.setting {
        val versions = Settings.buildScalaVersions.value
        if (versions.nonEmpty) versions else crossScalaVersions.value
      }
      else Def.setting(crossScalaVersions.value)
    }.value
  )

  /** Project settings: wires libraryDependencies and registers tasks. */
  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    dependenciesFromFile             := Settings.dependenciesFromFile.value,
    libraryDependencies             ++= Settings.libraryDependencies.value,
    inheritedDependencies            := Settings.inheritedDependencies.value,
    showLibraryDependencies          := Tasks.showLibraryDependencies.tag(Exclusive).value,
    updateDependencies               := Tasks.updateDependencies.tag(Exclusive).evaluated,
    updateScalaVersions              := Tasks.updateScalaVersions.tag(Exclusive).evaluated,
    install                          := Tasks.install.tag(Exclusive).evaluated,
    dependenciesCheck                := Nil,
    update                           := Tasks.updateWithChecks.value,
    allProjectDependencies           := update.value.allModules.toList,
    install / aggregate              := false,
    dependenciesManagedScalaVersions := Settings.projectScalaVersions.value.nonEmpty,
    scalaVersion := Def.settingDyn {
      val file = Settings.dependenciesFile.value
      if (file.exists()) Def.setting {
        val versions = Settings.projectScalaVersions.value
        if (versions.nonEmpty) versions.head else scalaVersion.value
      }
      else Def.setting(scalaVersion.value)
    }.value,
    crossScalaVersions := Def.settingDyn {
      val file = Settings.dependenciesFile.value
      if (file.exists()) Def.setting {
        val versions = Settings.projectScalaVersions.value
        if (versions.nonEmpty) versions else crossScalaVersions.value
      }
      else Def.setting(crossScalaVersions.value)
    }.value
  )

}
