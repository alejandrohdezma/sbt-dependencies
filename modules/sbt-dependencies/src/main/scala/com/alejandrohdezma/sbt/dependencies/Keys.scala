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

import sbt._
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName

import com.alejandrohdezma.sbt.dependencies.model.Dependency

class Keys {

  val dependenciesFromFile = settingKey[List[Dependency]]("Dependencies read from the file `project/dependencies`")

  val updateDependencies = inputKey[Unit]("Update dependencies to their latest versions")

  val updateScalaVersions = inputKey[Unit]("Update Scala versions to their latest versions")

  val inheritedDependencies = settingKey[Seq[ModuleID]]("Inherited dependencies from other projects")

  val install = inputKey[Unit]("Add new dependencies")

  val showLibraryDependencies = taskKey[Unit]("Show the library dependencies for the project")

  /** Map of variable names to resolver functions for variable-based dependency versions.
    *
    * The key is the variable name (without braces), and the value is a function that takes an OrganizationArtifactName
    * (from `"org" % "name"` or `"org" %% "name"`) and returns a ModuleID with the appropriate version.
    *
    * Example usage in build.sbt:
    * {{{
    * dependencyVersionVariables := Map(
    *   "zioVersion" -> { orgArtifact => orgArtifact % zio.zioVersion },
    *   "catsVersion" -> { orgArtifact => orgArtifact % "2.10.0" }
    * )
    * }}}
    */
  val dependencyVersionVariables =
    settingKey[Map[String, OrganizationArtifactName => ModuleID]](
      "Map of variable names to resolver functions for variable-based dependency versions"
    )

  val sbtDependenciesPluginOrganization =
    settingKey[String] {
      "Organization of the plugin to update in project/project/plugins.sbt"
    }.withRank(KeyRanks.Invisible)

  val sbtDependenciesPluginName =
    settingKey[String] {
      "Name of the plugin to update in project/project/plugins.sbt"
    }.withRank(KeyRanks.Invisible)

  /** All resolved library dependencies for the project, after conflict resolution and eviction. */
  val allProjectDependencies = taskKey[List[ModuleID]] {
    "All resolved library dependencies for the project, after conflict resolution and eviction"
  }

  /** Check functions that validate resolved dependencies after `update`.
    *
    * Each function receives the full list of resolved `ModuleID`s and can throw a `MessageOnlyException` to fail the
    * build when a policy is violated.
    *
    * @example
    *   {{{
    * // Using a plain function
    * dependenciesCheck += myCheck _
    *
    * // Using a Def.setting for access to other settings
    * dependenciesCheck += mySettingCheck.value
    *   }}}
    */
  val dependenciesCheck = settingKey[Seq[List[ModuleID] => Unit]] {
    "Check functions that validate resolved dependencies after update"
  }

  /** URLs pointing to artifact migration files in Scala Steward's HOCON format.
    *
    * When `updateDependencies` runs, it loads migrations from these URLs and automatically migrates dependencies to new
    * coordinates when a newer version is available under the new groupId/artifactId.
    *
    * Default: Scala Steward's public artifact-migrations.v2.conf
    *
    * @example
    *   {{{
    * // Disable all migrations
    * ThisBuild / dependencyMigrations := Nil
    *
    * // Add custom migrations URL
    * ThisBuild / dependencyMigrations += url("https://example.com/my-migrations.conf")
    *
    * // Use a local file
    * ThisBuild / dependencyMigrations += file("migrations.conf").toURI.toURL
    *   }}}
    */
  val dependencyMigrations = settingKey[List[java.net.URL]] {
    "URLs pointing to artifact migration files (Scala Steward HOCON format). Default: Scala Steward's public migrations"
  }

  /** Timeout in seconds for Coursier version resolution requests.
    *
    * When resolving available versions for a dependency, Coursier will wait at most this many seconds before timing
    * out.
    *
    * Default: 60 seconds
    *
    * @example
    *   {{{
    * // Increase timeout for slow networks
    * ThisBuild / dependencyResolverTimeout := 60
    *   }}}
    */
  val dependencyResolverTimeout = settingKey[Int] {
    "Timeout in seconds for Coursier version resolution requests. Default: 60"
  }

  /** URLs pointing to retracted-version files in Scala Steward's HOCON format.
    *
    * When `updateDependencies` runs, it loads `updates.retracted` entries from these URLs and automatically excludes
    * retracted versions from update candidates. When a dependency's current version is retracted, a warning is logged
    * with the reason and documentation URL.
    *
    * Default: Scala Steward's public default.scala-steward.conf
    *
    * @example
    *   {{{
    * // Disable all retractions
    * ThisBuild / dependencyUpdateRetractions := Nil
    *
    * // Add custom retraction URL
    * ThisBuild / dependencyUpdateRetractions += url("https://example.com/my-retractions.conf")
    *
    * // Use a local file
    * ThisBuild / dependencyUpdateRetractions += file("retractions.conf").toURI.toURL
    *   }}}
    */
  val dependencyUpdateRetractions = settingKey[List[java.net.URL]] {
    "URLs pointing to retracted-version files (Scala Steward HOCON format). Default: Scala Steward's public config"
  }

  /** URLs pointing to update-ignore files in Scala Steward's HOCON format.
    *
    * When `updateDependencies` runs, it loads `updates.ignore` entries from these URLs and automatically excludes
    * matching versions from update candidates.
    *
    * Default: Scala Steward's public default.scala-steward.conf
    *
    * @example
    *   {{{
    * // Disable all ignores
    * ThisBuild / dependencyUpdateIgnores := Nil
    *
    * // Add custom ignore URL
    * ThisBuild / dependencyUpdateIgnores += url("https://example.com/my-ignores.conf")
    *
    * // Use a local file
    * ThisBuild / dependencyUpdateIgnores += file("ignores.conf").toURI.toURL
    *   }}}
    */
  val dependencyUpdateIgnores = settingKey[List[java.net.URL]] {
    "URLs pointing to update-ignore files (Scala Steward HOCON format). Default: Scala Steward's public config"
  }

  /** URLs pointing to update-pin files in Scala Steward's HOCON format.
    *
    * When `updateDependencies` runs, it loads `updates.pin` entries from these URLs and restricts updates to versions
    * that match the pin's version pattern. Only artifacts matching a pin's groupId/artifactId are affected; unpinned
    * artifacts pass through freely.
    *
    * Default: Scala Steward's public default.scala-steward.conf
    *
    * @example
    *   {{{
    * // Disable all pins
    * ThisBuild / dependencyUpdatePins := Nil
    *
    * // Add custom pin URL
    * ThisBuild / dependencyUpdatePins += url("https://example.com/my-pins.conf")
    *
    * // Use a local file
    * ThisBuild / dependencyUpdatePins += file("pins.conf").toURI.toURL
    *   }}}
    */
  val dependencyUpdatePins = settingKey[List[java.net.URL]] {
    "URLs pointing to update-pin files (Scala Steward HOCON format). Default: Scala Steward's public config"
  }

  /** URLs pointing to post-update hook files in Scala Steward's HOCON format.
    *
    * After `updateAllDependencies` computes the dependency diff, hooks from these URLs are matched against updated
    * dependencies. Matching hooks are written to a JSON output file for CI consumption.
    *
    * Default: Scala Steward's public default.scala-steward.conf
    *
    * @example
    *   {{{
    * // Disable all post-update hooks
    * ThisBuild / dependencyPostUpdateHooks := Nil
    *
    * // Add custom hooks URL
    * ThisBuild / dependencyPostUpdateHooks += url("https://example.com/my-hooks.conf")
    *
    * // Use a local file
    * ThisBuild / dependencyPostUpdateHooks += file("hooks.conf").toURI.toURL
    *   }}}
    */
  val dependencyPostUpdateHooks = settingKey[List[java.net.URL]] {
    "URLs pointing to post-update hook files (Scala Steward HOCON format). Default: Scala Steward's public config"
  }

  /** URLs pointing to scalafix migration files in Scala Steward's HOCON format.
    *
    * After `updateAllDependencies` computes the dependency diff, migrations from these URLs are matched against updated
    * dependencies using version range checks. Matching migrations are written to a JSON output file as scalafix
    * commands for CI consumption.
    *
    * Default: Scala Steward's public scalafix-migrations.conf
    *
    * @example
    *   {{{
    * // Disable all scalafix migrations
    * ThisBuild / dependencyScalafixMigrations := Nil
    *
    * // Add custom migrations URL
    * ThisBuild / dependencyScalafixMigrations += url("https://example.com/my-migrations.conf")
    *
    * // Use a local file
    * ThisBuild / dependencyScalafixMigrations += file("migrations.conf").toURI.toURL
    *   }}}
    */
  val dependencyScalafixMigrations = settingKey[List[java.net.URL]] {
    "URLs pointing to scalafix migration files (Scala Steward HOCON format). Default: Scala Steward's public migrations"
  }

  /** Maximum number of dependencies resolved concurrently.
    *
    * Controls the size of the thread pool used when checking dependency versions.
    *
    * Default: `Runtime.getRuntime.availableProcessors`
    *
    * @example
    *   {{{
    * // Limit to 4 concurrent resolutions
    * ThisBuild / dependencyResolverParallelism := 4
    *   }}}
    */
  val dependencyResolverParallelism = settingKey[Int] {
    "Maximum number of dependencies resolved concurrently. Default: available processors"
  }

}

object Keys extends Keys
