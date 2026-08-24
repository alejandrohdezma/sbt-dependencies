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

import com.alejandrohdezma.sbt.dependencies.bom.BomReader
import com.alejandrohdezma.sbt.dependencies.bom.ModuleFetcher
import com.alejandrohdezma.sbt.dependencies.constraints.UpdateFilter
import com.alejandrohdezma.sbt.dependencies.finders.Finders
import com.alejandrohdezma.sbt.dependencies.finders.Utils
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.DependencyOps._
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.alejandrohdezma.sbt.dependencies.model.Group
import com.alejandrohdezma.string.box._

/** SBT input tasks for managing dependencies. */
class Tasks {

  /** Updates dependencies to their latest versions based on the filter and version constraints. */
  val updateDependencies = Def.inputTask {
    implicit val logger: Logger   = streams.value.log
    implicit val finders: Finders = Finders.fromState(state.value, scalaVersion.value, crossScalaVersions.value)

    val file        = Settings.dependenciesFile.value
    val group       = Settings.currentGroup.value
    val groupExists = file.hasGroup(group)
    val bomPins     = Keys.dependenciesFromBom.value
    val scalaBinary = (update / scalaBinaryVersion).value
    val variables   = Keys.dependencyVersionVariables.value

    val dependencies = file
      .read(group, variables)
      .map(_.resolveBom(bomPins, scalaBinary))

    val filter = updateFilterParser.parsed

    val isSbtBuild = Settings.isSbtBuild.value
    val sbtBinary  = (pluginCrossBuild / sbtBinaryVersion).value
    val extracted  = Project.extract(state.value)

    val inheritedGroups = buildDependencies.value.classpathTransitive
      .getOrElse(thisProjectRef.value, Nil)
      .map(ref => Group(extracted.get(ref / name)))

    implicit val fetcher: ModuleFetcher = Settings.bomFetcher.value

    if (!groupExists) {
      // Group not in YAML file - silently skip
    } else if (dependencies.isEmpty) {
      logger.info(s"\n∅  No dependencies found for `$group`\n")
    } else {
      logger.info(s"\n↻ Updating ${filter.show} dependencies for `$group`\n")

      val filtered = dependencies.filterNot(filter.matches)

      val parallelism = Keys.dependencyResolverParallelism.value

      val updated = Utils.resolveLatestVersions(dependencies.filter(filter.matches), parallelism)

      updated.foreach(finders.retractionFinder.warnIfRetracted(_))

      val toWrite = filtered ++ updated

      // A BOM bump can drop the old coordinates of a migrated `*` dependency, so the whole group — matching the
      // filter or not — is checked against the pins the visible BOMs have at the versions this run actually
      // produces. Lines whose BOMs didn't move keep their old coordinates pinned and are never rewritten.
      val migrated = if (toWrite.exists(_.hasBomManagedMigration)) {
        def bomsOf(g: Group): List[Dependency] = file.read(g, variables).filter(_.configuration === "bom")

        // Visible BOMs in precedence order (own group, common-settings, dependsOn groups). Own-group entries come
        // from the list being written; common-settings from the file (updateCommonDependencies runs earlier, so it's
        // already bumped when part of updateAllDependencies); dependsOn groups' entries matching the filter are
        // predicted with the same version lookup their own task instance uses — that instance applies the same
        // filter, so the result is independent of aggregation ordering.
        val newPins = toWrite
          .filter(_.configuration === "bom")
          .++(if (isSbtBuild) Nil else bomsOf(Group.`common-settings`))
          .++(inheritedGroups.flatMap(bomsOf).map(dep => if (filter.matches(dep)) dep.findLatestVersion else dep))
          .distinct
          .map(_.toModuleID(sbtBinary, scalaBinary))
          .flatMap(BomReader.read(_, scalaBinary))

        toWrite.map(_.migrateBomManaged(newPins, scalaBinary))
      } else toWrite

      file.write(group, migrated)
    }
  }

  /** Replaces every dependency version pinned by a visible BOM with the `*` marker. */
  val useBomManagedVersions = Def.task {
    implicit val logger: Logger = streams.value.log

    val file        = Settings.dependenciesFile.value
    val group       = Settings.currentGroup.value
    val bomPins     = Keys.dependenciesFromBom.value
    val scalaBinary = (update / scalaBinaryVersion).value

    if (file.hasGroup(group) && bomPins.nonEmpty) {
      val dependencies = file.read(group, Keys.dependencyVersionVariables.value)

      logger.info(s"\n↻ Using BOM-managed versions for `$group`\n")

      val rewritten = dependencies.map(_.useBomManagedVersion(bomPins, scalaBinary))

      val changed = rewritten.zip(dependencies).exists { case (a, b) => a.version !== b.version }

      if (changed) file.write(group, rewritten)
      else logger.info(s" ↳ $GREEN✓$RESET Nothing to replace")
    }
  }

  /** Installs a dependency, validating if the provided version is available or finding the latest version if version is
    * not provided.
    */
  val install = Def.inputTask {
    implicit val logger: Logger   = streams.value.log
    implicit val finders: Finders = Finders.fromState(state.value, scalaVersion.value)

    val file         = Settings.dependenciesFile.value
    val group        = Settings.currentGroup.value
    val dependencies = file.read(group, Keys.dependencyVersionVariables.value)
    val dependency   = Dependency.parseIncludingMissingVersion(installParser.parsed)

    Dependency.validateBomRestrictionsOrFail(dependency)

    val _ = Settings.resolveBomVersion(dependency, Keys.dependenciesFromBom.value, (update / scalaBinaryVersion).value)

    logger.info(s"➕ [$group] $YELLOW${dependency.toLine}$RESET")

    val updated = dependencies.filterNot(_.isSameArtifact(dependency)) :+ dependency

    file.write(group, updated)
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
          val version      = s""""${dep.version}"""".padTo(maxVersionLength + 2, ' ')

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
    implicit val logger: Logger   = streams.value.log
    implicit val finders: Finders = Finders.fromState(state.value, scalaVersion.value)

    val file        = Settings.dependenciesFile.value
    val group       = Settings.currentGroup.value
    val groupExists = file.hasGroup(group)
    val versions    = file.readScalaVersions(group)

    if (groupExists && versions.nonEmpty) {
      logger.info(s"\n↻ Updating Scala versions for `$group`\n")

      val updated = versions.map { version =>
        val latest = Utils.findLatestScalaVersion(version)

        if (latest === version) {
          logger.info(s" ↳ $GREEN✓$RESET $GREEN$version$RESET")
          version
        } else {
          logger.info(
            s" ↳ $YELLOW⬆$RESET $YELLOW$version$RESET -> $CYAN$latest$RESET"
          )
          latest
        }
      }

      file.writeScalaVersions(group, updated)
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

  def allProjectDependencies: Def.Initialize[Task[List[ModuleID]]] = Def.task {
    val report = update.value

    val priority = List("compile", "runtime", "provided", "test", "it")

    val configByModule = scala.collection.mutable.LinkedHashMap.empty[(String, String), String]

    for {
      scope         <- priority
      configuration <- report.configurations.find(_.configuration.name === scope).toSeq
      mod           <- configuration.modules
    } {
      val key = (mod.module.organization, mod.module.name)
      if (!configByModule.contains(key)) { val _ = configByModule.put(key, scope) }
    }

    report.allModules.toList.map { m =>
      val config = configByModule.getOrElse((m.organization, m.name), m.configurations.getOrElse("compile"))
      m.withConfigurations(Some(config))
    }
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
