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
import sbt.complete.DefaultParsers._
import sbt.internal.util.complete.Parser
import sbt.{Keys => _, _}

import com.alejandrohdezma.sbt.dependencies.Eq._
import com.alejandrohdezma.string.box._

/** SBT input tasks for managing dependencies. */
@SuppressWarnings(Array("scalafix:Disable.scala.collection.parallel"))
class Tasks {

  /** Updates dependencies to their latest versions based on the filter and version constraints. */
  val updateDependencies = Def.inputTask {
    implicit val logger: Logger                     = streams.value.log
    implicit val versionFinder: Utils.VersionFinder = Utils.VersionFinder.fromCoursier(scalaBinaryVersion.value)

    val file         = Settings.dependenciesFile.value
    val group        = Settings.currentGroup.value
    val groupExists  = DependenciesFile.hasGroup(file, group)
    val dependencies = DependenciesFile.read(file, group, Keys.dependencyVersionVariables.value)
    val filter       = updateFilterParser.parsed

    if (!groupExists) {
      // Group not in YAML file - silently skip
    } else if (dependencies.isEmpty) {
      logger.info(s"\n🫙  No dependencies found for `$group`\n")
    } else {
      logger.info(s"\n🔄 Updating ${filter.show} dependencies for `$group`\n")

      val filtered = dependencies.filterNot(filter.matches)

      val updated =
        dependencies.par
          .filter(filter.matches)
          .map {
            // Pinned version with Exact marker
            case dep: Dependency.WithNumericVersion if dep.version.marker.isExact =>
              logger.info(s" ↳ 📌 $CYAN${dep.toLine}$RESET")
              dep

            // Numeric version
            case dep: Dependency.WithNumericVersion =>
              val updated = dep.findLatestVersion

              if (updated.version.isSameVersion(dep.version)) {
                logger.info(s" ↳ ✅ $GREEN${dep.toLine}$RESET")
                dep
              } else {
                logger.info(s" ↳ ⬆️ $YELLOW${dep.toLine}$RESET -> $CYAN${updated.version.show}$RESET")
                updated
              }

            // Variable version
            case dep: Dependency.WithVariableVersion =>
              val updated = dep.findLatestVersion

              if (updated.version.isSameVersion(dep.version.resolved))
                logger.info(s" ↳ ✅ $GREEN${dep.toLine}$RESET (resolves to `${dep.version.toVersionString}`)")
              else
                logger.info {
                  s" ↳ 🔗 $CYAN${dep.toLine}$RESET (resolves to `${dep.version.toVersionString}`, " +
                    s"latest: `$YELLOW${updated.version.toVersionString}$RESET`)"
                }

              dep
          }
          .toList

      DependenciesFile.write(file, group, filtered ++ updated)
    }
  }

  /** Installs a dependency, validating if the provided version is available or finding the latest version if version is
    * not provided.
    */
  val install = Def.inputTask {
    implicit val logger: Logger                     = streams.value.log
    implicit val versionFinder: Utils.VersionFinder = Utils.VersionFinder.fromCoursier(scalaBinaryVersion.value)

    val file         = Settings.dependenciesFile.value
    val group        = Settings.currentGroup.value
    val dependencies = DependenciesFile.read(file, group, Keys.dependencyVersionVariables.value)
    val dependency   = Dependency.parse(installParser.parsed)

    logger.info(s"➕ [$group] $YELLOW${dependency.toLine}$RESET")

    val updated = dependencies.filterNot(_.isSameArtifact(dependency)) :+ dependency

    DependenciesFile.write(file, group, updated)
  }

  /** Shows the library dependencies for the current project in a formatted, colored output. */
  val showLibraryDependencies = Def.task {
    val projectName = name.value

    // Get inherited dependencies from projects this one depends on
    val inheritedDependencies = Keys.inheritedDependencies.value

    val allDependencies = libraryDependencies.value ++ inheritedDependencies

    val (maxOrgLength, maxNameLength, maxVersionLength) =
      allDependencies.foldLeft((0, 0, 0)) { case ((org, name, rev), dep) =>
        (org.max(dep.organization.length), name.max(dep.name.length), rev.max(dep.revision.length))
      }

    val directDependencies = libraryDependencies.value.map(m => (m.organization, m.name)).toSet

    val dependencies = allDependencies
      .map(Dependency.fromModuleID(_))
      .flatMap(_.toList)
      .distinct
      .sortBy(dep => (dep.organization, dep.name))
      .groupBy(_.configuration)
      .toList
      .sortBy(_._1)
      .map { case (configurations, deps) =>
        val config =
          if (configurations === "compile") ""
          else s"$CYAN% $YELLOW${configurations.capitalize}$RESET"

        deps.map { dep =>
          val organization = s""""${dep.organization}"""".padTo(maxOrgLength + 2, ' ')
          val cross        = if (dep.isCross) s"$CYAN%%$RESET" else s"$CYAN %$RESET"
          val depName      = s""""${dep.name}"""".padTo(maxNameLength + 2, ' ')
          val version      = s""""${dep.version.toVersionString}"""".padTo(maxVersionLength + 2, ' ')

          if (directDependencies.contains((dep.organization, dep.name)))
            s"$GREEN$organization$RESET $cross $GREEN$depName$RESET $CYAN%$RESET $GREEN$version$RESET $config"
          else
            s"$YELLOW$organization$RESET $cross $YELLOW$depName$RESET $CYAN%$RESET $YELLOW$version$RESET $config"
        }.mkString("\n")
      }
      .mkString("\n")

    val legend = s"$GREEN▇$RESET = Direct dependency\n$YELLOW▇$RESET = Inherited from other projects"

    streams.value.log.info(s"$UNDERLINED$BOLD$MAGENTA$projectName$RESET\n\n$dependencies\n\n$legend".boxed)
  }

  /** Updates Scala versions to their latest versions within the same minor line. */
  val updateScalaVersions = Def.inputTask {
    implicit val logger: Logger                     = streams.value.log
    implicit val versionFinder: Utils.VersionFinder = Utils.VersionFinder.fromCoursier(scalaBinaryVersion.value)

    val file        = Settings.dependenciesFile.value
    val group       = Settings.currentGroup.value
    val groupExists = DependenciesFile.hasGroup(file, group)
    val versions    = DependenciesFile.readScalaVersions(file, group)

    if (!groupExists) {
      // Group not in YAML file - silently skip
    } else if (versions.isEmpty) {
      logger.info(s"\n🫙 No scala-versions configured for `$group`\n")
    } else {
      logger.info(s"\n🔄 Updating Scala versions for `$group`\n")

      val updated = versions.map { version =>
        val latest = Utils.findLatestScalaVersion(version)

        if (latest === version) {
          logger.info(s" ↳ ✅ $GREEN${version.toVersionString}$RESET")
          version
        } else {
          logger.info(s" ↳ ⬆️ $YELLOW${version.toVersionString}$RESET -> $CYAN${latest.toVersionString}$RESET")
          latest
        }
      }

      DependenciesFile.writeScalaVersions(file, group, updated)
    }
  }

  /** Run all dependency check functions after resolution completes. If any check throws, the `update` task (and
    * anything depending on it) will fail.
    */
  def updateWithChecks = Def.task {
    val report = update.value

    Keys.dependenciesCheck.value.foreach(_(report.allModules.toList))

    report
  }

  /** Parser for updateDependencies filter: `[org:artifact]`, `[org:]`, `[:artifact]`, or empty for all */
  private val updateFilterParser: Parser[UpdateFilter] = {
    val regex = """^([^:]+)?:([^:]+)?$""".r

    val orgAndArtifact = token(NotSpace, "<org:artifact>").map {
      case regex(org, null) => UpdateFilter.ByOrg(org)      // scalafix:ok
      case regex(null, art) => UpdateFilter.ByArtifact(art) // scalafix:ok
      case regex(org, art)  => UpdateFilter.ByOrgAndArtifact(org, art)
      case _                => UpdateFilter.All
    }

    (Space ~> orgAndArtifact) ?? UpdateFilter.All
  }

  /** Parser for install: `<dependency>` */
  private val installParser: Parser[String] =
    Space ~> token(NotSpace, "<dependency>")

}

object Tasks extends Tasks
