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
import sbt.librarymanagement.UnresolvedWarningConfiguration
import sbt.{Keys => _, _}

import com.alejandrohdezma.sbt.dependencies.bom.BomReader
import com.alejandrohdezma.sbt.dependencies.bom.ModuleFetcher
import com.alejandrohdezma.sbt.dependencies.constraints.UpdateFilter
import com.alejandrohdezma.sbt.dependencies.finders.Finders
import com.alejandrohdezma.sbt.dependencies.finders.Utils
import com.alejandrohdezma.sbt.dependencies.io.BomAdoptionReport
import com.alejandrohdezma.sbt.dependencies.model.BomAdoption
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.DependencyOps._
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.alejandrohdezma.sbt.dependencies.model.Group
import com.alejandrohdezma.string.box._
import lmcoursier.CoursierDependencyResolution

/** SBT input tasks for managing dependencies. */
class Tasks {

  /** Updates dependencies to their latest versions based on the filter and version constraints. A `*` line that no
    * visible BOM's new version pins (and no artifact migration moves) is written with the version it resolved to before
    * the update, and listed in `target/sbt-dependencies/bom-dropped/<group>.md` for the GitHub Action's report — one
    * file per group, deleted when there is nothing to report.
    */
  val updateDependencies = Def.inputTask {
    implicit val logger: Logger   = streams.value.log
    implicit val finders: Finders = Finders.fromState(state.value, scalaVersion.value, crossScalaVersions.value)

    val file        = Settings.dependenciesFile.value
    val group       = Settings.currentGroup.value
    val groupExists = file.hasGroup(group)
    val bomPins     = Keys.dependenciesFromBom.value
    val scalaBinary = (update / scalaBinaryVersion).value
    val variables   = Keys.dependencyVersionVariables.value

    val droppedReport =
      (ThisBuild / baseDirectory).value / "target" / "sbt-dependencies" / "bom-dropped" / s"$group.md"

    val raw = file.read(group, variables)

    val filter = UpdateFilter.parser.parsed

    val isSbtBuild = Settings.isSbtBuild.value
    val sbtBinary  = (pluginCrossBuild / sbtBinaryVersion).value
    val extracted  = Project.extract(state.value)

    val inheritedGroups = buildDependencies.value.classpathTransitive
      .getOrElse(thisProjectRef.value, Nil)
      .map(ref => Group(extracted.get(ref / name)))

    implicit val fetcher: ModuleFetcher = Settings.bomFetcher.value

    if (!groupExists) {
      // Group not in YAML file - silently skip
    } else if (raw.isEmpty) {
      logger.info(s"\n∅  No dependencies found for `$group`\n")
    } else {
      logger.info(s"\n↻ Updating ${filter.show} dependencies for `$group`\n")

      def bomsOf(g: Group): List[Dependency] = file.read(g, variables).filter(_.configuration === "bom")

      // The pins the visible BOMs have at the versions this run produces, in precedence order (own group,
      // common-settings, dependsOn groups). Own-group and dependsOn entries matching the filter are predicted with the
      // same version lookup the update itself uses (cached, so the update's own lookup is free), which makes the result
      // independent of aggregation ordering; common-settings comes from the file, already bumped by
      // updateCommonDependencies when part of updateAllDependencies. `*` lines resolve against these pins so their
      // "resolves to" reflects the BOM bump happening in this very run, falling back to the load-time pins for
      // artifacts the new BOM no longer provides (the migration and pinning steps below deal with those).
      def predicted(boms: Seq[Dependency]): Seq[Dependency] =
        boms.map(dep => if (filter.matches(dep)) dep.findLatestVersion else dep)

      val newPins = predicted(raw.filter(_.configuration === "bom")).toList
        .++(if (isSbtBuild) Nil else bomsOf(Group.`common-settings`))
        .++(predicted(inheritedGroups.flatMap(bomsOf)))
        .distinct
        .map(_.toModuleID(sbtBinary, scalaBinary))
        .flatMap(BomReader.read(_, scalaBinary))

      val dependencies = raw.map { dependency =>
        dependency.withVersion(dependency.resolveBom(newPins, scalaBinary).resolveBom(bomPins, scalaBinary).version)
      }

      val filtered = dependencies.filterNot(filter.matches)

      val parallelism = Keys.dependencyResolverParallelism.value

      val updated = Utils.resolveLatestVersions(dependencies.filter(filter.matches), parallelism)

      updated.foreach(finders.retractionFinder.warnIfRetracted(_))

      val toWrite = filtered ++ updated

      // A BOM bump can drop the old coordinates of a migrated `*` dependency, so the whole group — matching the
      // filter or not — is checked against the new pins. Lines whose BOMs didn't move keep their old coordinates
      // pinned and are never rewritten.
      val migrated =
        if (toWrite.exists(_.hasBomManagedMigration)) toWrite.map(_.migrateBomManaged(newPins, scalaBinary))
        else toWrite

      val pinned = migrated.map(_.pinIfDroppedFromBom(newPins, scalaBinary))

      file.write(group, pinned)

      val dropped = migrated.zip(pinned).collect {
        case (before, after) if before.version.isBom && !after.version.isBom =>
          s"- `$group`: `${before.toLine}` → `${after.toLine}`\n"
      }

      if (dropped.isEmpty) IO.delete(droppedReport) else IO.write(droppedReport, dropped.mkString)
    }
  }

  /** Replaces every dependency version pinned by a visible BOM with the `*` marker; `--safe` leaves marked (`=`, `^`,
    * `~`) and variable versions as is. What happened to the group is written to
    * `target/sbt-dependencies/bom-managed/<group>.md` for the GitHub Action's report — one file per group, so
    * aggregated runs need no coordination — and deleted when there is nothing to report.
    */
  val useBomManagedVersions = Def.inputTask {
    implicit val logger: Logger = streams.value.log

    val safe        = safeParser.parsed
    val file        = Settings.dependenciesFile.value
    val group       = Settings.currentGroup.value
    val bomPins     = Keys.dependenciesFromBom.value
    val scalaBinary = (update / scalaBinaryVersion).value
    val reportFile  = (ThisBuild / baseDirectory).value / "target" / "sbt-dependencies" / "bom-managed" / s"$group.md"

    val adoptions =
      if (file.hasGroup(group) && bomPins.nonEmpty) {
        val dependencies = file.read(group, Keys.dependencyVersionVariables.value)

        logger.info(s"\n↻ Using BOM-managed versions for `$group`${if (safe) " (safe mode)" else ""}\n")

        val adoptions = dependencies.map(_.adoptBomManagedVersion(bomPins, scalaBinary, safe))

        val adopted = adoptions.collect { case adoption: BomAdoption.Adopted => adoption }

        if (adopted.nonEmpty) file.write(group, adoptions.map(_.dependency))
        else logger.info(s" ↳ $GREEN✓$RESET Nothing to replace")

        adoptions
      } else Nil

    BomAdoptionReport.render(group, adoptions) match {
      case Some(report) => IO.write(reportFile, report)
      case None         => IO.delete(reportFile)
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

  /** When set on the state, [[updateWithChecks]] skips `dependenciesCheck`. Set by the update snapshots, which only
    * record what resolves: a check failing on the updated dependencies must not hide them from the diff.
    */
  val skipDependenciesCheck = AttributeKey[Boolean]("sbtDependenciesSkipDependenciesCheck")

  /** Run all dependency check functions after resolution completes. If any check throws, the `update` task (and
    * anything depending on it) will fail. The checks are skipped when the state carries [[skipDependenciesCheck]]. Also
    * warns about every declared dependency that resolved to a version other than the declared one — a transitive
    * dependency outvoting it, or an override forcing it — and, following `dependencyOverridesLoweringCheck`, about
    * every `overrides = true` pin below the version the dependency graph resolves to without it.
    */
  def updateWithChecks = Def.task {
    val report = update.value
    val log    = streams.value.log
    val checks = Keys.dependenciesCheck.value
    val skip   = state.value.get(skipDependenciesCheck).contains(true)

    if (!skip) checks.foreach(_(report.allModules.toList))

    val declared =
      Keys.moduleIdsFromFile.value.map(CrossVersion(scalaVersion.value, (update / scalaBinaryVersion).value))
    val resolved = report.configurations.flatMap(_.modules).filterNot(_.evicted).map(_.module)

    revisionChanges(declared, resolved).foreach { case (module, actual) =>
      log.warn {
        s"${module.organization}:${module.name} is declared at ${module.revision} but resolved to $actual — another " +
          "dependency requires that version or an override forces it. Hold the dependency pulling it in or mark the " +
          "line with `overrides = true`."
      }
    }

    val lowered = loweredOverrides(Keys.dependencyOverridesFromFile.value, resolvedWithoutFileOverrides.value).map {
      case (module, required) =>
        s"${module.organization}:${module.name} is forced to ${module.revision} by an `overrides = true` line, " +
          s"below the $required the dependency graph requires. Code compiled against $required can fail at " +
          "runtime. Raise the pinned version or stop overriding it."
    }

    Keys.dependencyOverridesLoweringCheck.value match {
      case LoweringCheck.Error if lowered.nonEmpty && !skip => sys.error(lowered.mkString("\n"))
      case _                                                => lowered.foreach(log.warn(_))
    }

    report
  }

  /** The modules this project resolves to once the `overrides = true` pins are lifted (every other
    * `dependencyOverrides` entry stays). A second coursier resolution, so it only runs for projects inheriting such
    * pins, and not at all when `dependencyOverridesLoweringCheck` is `Off`. Coursier reads overrides from
    * `csrConfiguration`'s force versions rather than from the module descriptor, so the pins are lifted from both.
    */
  private def resolvedWithoutFileOverrides: Def.Initialize[Task[Seq[ModuleID]]] = Def.taskDyn {
    val fromFile = Keys.dependencyOverridesFromFile.value

    Keys.dependencyOverridesLoweringCheck.value match {
      case LoweringCheck.Off                        => Def.task(Seq.empty[ModuleID])
      case _ if fromFile.isEmpty                    => Def.task(Seq.empty[ModuleID])
      case LoweringCheck.Warn | LoweringCheck.Error =>
        Def.task {
          val log    = streams.value.log
          val config = updateConfiguration.value

          def fromFileOverride(organization: String, name: String) =
            fromFile.exists(o => o.organization === organization && o.name === name)

          val coursier = csrConfiguration.value

          val resolution = CoursierDependencyResolution(coursier.withForceVersions(coursier.forceVersions.filterNot {
            case (module, _) => fromFileOverride(module.organization.value, module.name.value)
          }))

          moduleSettings.value match {
            case settings: ModuleDescriptorConfiguration =>
              val module = resolution.moduleDescriptor(
                settings.withOverrides(settings.overrides.filterNot(o => fromFileOverride(o.organization, o.name)))
              )

              resolution.update(module, config, UnresolvedWarningConfiguration(), log) match {
                case Right(report) => report.configurations.flatMap(_.modules).filterNot(_.evicted).map(_.module)
                case Left(_)       => Seq.empty
              }

            case _ => Seq.empty
          }
        }
    }
  }

  /** Modules of `forced` whose `(organization, name)` resolves to a higher revision in `unforced`, paired with the
    * highest such revision.
    */
  private[dependencies] def loweredOverrides(forced: Seq[ModuleID], unforced: Seq[ModuleID]): Seq[(ModuleID, String)] =
    forced.flatMap { module =>
      unforced
        .filter(m => m.organization === module.organization && m.name === module.name)
        .map(_.revision)
        .reduceOption((a, b) => if (PluginCompat.isLowerVersion(a, b)) b else a)
        .filter(PluginCompat.isLowerVersion(module.revision, _))
        .map(module -> _)
    }

  /** Modules of `from` whose `(organization, name)` appears in `to` at a different revision, paired with that revision.
    * The first `to` entry for a module wins.
    */
  private[dependencies] def revisionChanges(from: Seq[ModuleID], to: Seq[ModuleID]): Seq[(ModuleID, String)] =
    from.flatMap { module =>
      to.find(m => m.organization === module.organization && m.name === module.name)
        .filter(_.revision !== module.revision)
        .map(module -> _.revision)
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

  /** Parser for useBomManagedVersions: an optional `--safe` flag */
  private val safeParser: Parser[Boolean] =
    (Space ~> token("--safe")).?.map(_.isDefined)

  /** Parser for install: `<dependency>` */
  private val installParser: Parser[String] =
    Space ~> token(NotSpace, "<dependency>")

}

object Tasks extends Tasks
