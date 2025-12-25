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
import sbt.complete.DefaultParsers._
import sbt.internal.util.complete.Parser
import sbt.{Keys => _, _}

import com.alejandrohdezma.sbt.dependencies.Eq._

/** SBT input tasks for managing dependencies. */
@SuppressWarnings(Array("scalafix:Disable.scala.collection.parallel"))
class Tasks {

  /** Updates dependencies to their latest versions based on the filter and version constraints. */
  val updateDependencies = Def.inputTask {
    implicit val logger: Logger                     = streams.value.log
    implicit val versionFinder: Utils.VersionFinder = Utils.VersionFinder.fromCoursier(scalaBinaryVersion.value)

    val file         = Settings.dependenciesFile.value
    val dependencies = DependenciesFile.read(file)
    val filter       = updateFilterParser.parsed
    val group        = Settings.currentGroup.value

    logger.info(s"\n🔄 Updating ${filter.show} dependencies for `$group`\n")

    val updated =
      dependencies.par.map {
        case dep if filter.matches(dep) && dep.group === group =>
          dep.update match {
            case latest if dep.version === latest =>
              logger.info(s" ↳ ✅ $GREEN${dep.toLine}$RESET")
              dep

            case latest =>
              logger.info(s" ↳ ⬆️  $YELLOW${dep.toLine}$RESET -> $CYAN$latest$RESET")
              dep.withVersion(latest)
          }
        case dep => dep
      }.toList

    DependenciesFile.write(updated, file)
  }

  /** Installs a dependency, validating if the provided version is available or finding the latest version if version is
    * not provided.
    */
  val install = Def.inputTask {
    implicit val logger: Logger                     = streams.value.log
    implicit val versionFinder: Utils.VersionFinder = Utils.VersionFinder.fromCoursier(scalaBinaryVersion.value)

    val file         = Settings.dependenciesFile.value
    val dependencies = DependenciesFile.read(file)
    val dependency   = Dependency.parse(installParser.parsed, Settings.currentGroup.value)

    logger.info(s"➕ [${dependency.group}] $YELLOW${dependency.toLine}$RESET")

    val updated = dependencies.filterNot(_.isSameArtifact(dependency)) :+ dependency

    DependenciesFile.write(updated, file)
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
