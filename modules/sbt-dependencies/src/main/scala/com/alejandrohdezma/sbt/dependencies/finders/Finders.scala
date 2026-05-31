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

package com.alejandrohdezma.sbt.dependencies.finders

import sbt.Keys._
import sbt._

import com.alejandrohdezma.sbt.dependencies.Keys
import com.alejandrohdezma.sbt.dependencies.ListOps
import com.alejandrohdezma.sbt.dependencies.constraints.ConfigCache
import com.alejandrohdezma.sbt.dependencies.model.ScalaVersion
import coursier.MavenRepository
import coursier.Resolve

/** Bundle of every resolver-pipeline component needed for one command/task invocation.
  *
  * `Finders` is constructed once per command/task — typically via [[Finders.fromState]] — and passed as a single
  * `implicit Finders` through `Utils.findLatestVersion`, `Dependency.findLatestVersion`, `Scalafmt.updateVersion`, etc.
  * Each member is itself declared `implicit val`, so callers can write `import finders._` to bring every finder into
  * implicit scope at once.
  *
  * Bundling lets us add or remove pipeline pieces (cooldown, age-based filtering, etc.) without touching every method
  * signature in the resolver chain — the `Finders` trait grows or shrinks and existing call sites keep working.
  */
trait Finders {

  /** Resolves the cooldown (minimum age) that applies to a `(org, name, version)` triple, if any. `None` means no
    * cooldown rule matches — the version is accepted without an age check.
    */
  implicit val cooldownFinder: CooldownFinder

  def withCooldownFinder(cooldownFinder: CooldownFinder): Finders

  /** Decides whether a candidate version is at least `minimumAge` old. Consulted by `Utils.findLatestVersion` only
    * after `cooldownFinder` returns a `Some(minimumAge)` for the candidate. Failures (network errors, missing header)
    * degrade to "old enough" so updates aren't blocked on transient I/O.
    */
  implicit val ageChecker: AgeChecker

  def withAgeChecker(ageChecker: AgeChecker): Finders

  /** Filters out versions matching an `updates.ignore` entry (typically loaded from Scala Steward's published config).
    * Composed into `versionFinder` via `VersionFinder.ignoringVersions` in [[Finders.fromState]].
    */
  implicit val ignoreFinder: IgnoreFinder

  def withIgnoreFinder(ignoreFinder: IgnoreFinder): Finders

  /** Maps a dependency's old `(groupId, artifactId)` to its post-migration coordinates. Consulted by
    * `Utils.findLatestVersion` when resolving an artifact whose location has moved.
    */
  implicit val migrationFinder: MigrationFinder

  def withMigrationFinder(migrationFinder: MigrationFinder): Finders

  /** Restricts a dependency's allowed versions to those matching an `updates.pin` entry. Composed into `versionFinder`
    * via `VersionFinder.pinningVersions` in [[Finders.fromState]].
    */
  implicit val pinFinder: PinFinder

  def withPinFinder(pinFinder: PinFinder): Finders

  /** Looks up retraction metadata (`updates.retracted` entries) for a given coordinate. Composed into `versionFinder`
    * to exclude retracted versions from candidates, and also called explicitly by the update commands to surface a
    * warning when the retained version itself was retracted.
    */
  implicit val retractionFinder: RetractionFinder

  def withRetractionFinder(retractionFinder: RetractionFinder): Finders

  /** Looks up available versions for an artifact via Coursier. In [[Finders.fromState]] this is the standard chain:
    * `fromCoursier → cached → ignoringVersions → excludingRetracted → pinningVersions`.
    */
  implicit val versionFinder: VersionFinder

  def withVersionFinder(versionFinder: VersionFinder): Finders

  /** Scala version used to derive the Maven artifact name (`name_2.13`, `name_2.12_1.0`, etc.). Threaded through so
    * `VersionFinder.mavenArtifactName` callers don't need a separate implicit.
    */
  implicit val scalaVersion: ScalaVersion

  def withScalaVersion(scalaVersion: ScalaVersion): Finders

}

object Finders {

  private class Impl(
      implicit override val cooldownFinder: CooldownFinder,
      implicit override val ageChecker: AgeChecker,
      implicit override val ignoreFinder: IgnoreFinder,
      implicit override val migrationFinder: MigrationFinder,
      implicit override val pinFinder: PinFinder,
      implicit override val retractionFinder: RetractionFinder,
      implicit override val versionFinder: VersionFinder,
      implicit override val scalaVersion: ScalaVersion
  ) extends Finders {

    override def withCooldownFinder(_cooldownFinder: CooldownFinder): Finders =
      new Impl()(_cooldownFinder, ageChecker, ignoreFinder, migrationFinder, pinFinder, retractionFinder, versionFinder,
        scalaVersion)

    override def withAgeChecker(_ageChecker: AgeChecker): Finders =
      new Impl()(cooldownFinder, _ageChecker, ignoreFinder, migrationFinder, pinFinder, retractionFinder, versionFinder,
        scalaVersion)

    override def withIgnoreFinder(_ignoreFinder: IgnoreFinder): Finders =
      new Impl()(cooldownFinder, ageChecker, _ignoreFinder, migrationFinder, pinFinder, retractionFinder, versionFinder,
        scalaVersion)

    override def withMigrationFinder(_migrationFinder: MigrationFinder): Finders =
      new Impl()(cooldownFinder, ageChecker, ignoreFinder, _migrationFinder, pinFinder, retractionFinder, versionFinder,
        scalaVersion)

    override def withPinFinder(_pinFinder: PinFinder): Finders =
      new Impl()(cooldownFinder, ageChecker, ignoreFinder, migrationFinder, _pinFinder, retractionFinder, versionFinder,
        scalaVersion)

    override def withRetractionFinder(_retractionFinder: RetractionFinder): Finders =
      new Impl()(cooldownFinder, ageChecker, ignoreFinder, migrationFinder, pinFinder, _retractionFinder, versionFinder,
        scalaVersion)

    override def withVersionFinder(_versionFinder: VersionFinder): Finders =
      new Impl()(cooldownFinder, ageChecker, ignoreFinder, migrationFinder, pinFinder, retractionFinder, _versionFinder,
        scalaVersion)

    override def withScalaVersion(_scalaVersion: ScalaVersion): Finders =
      new Impl()(cooldownFinder, ageChecker, ignoreFinder, migrationFinder, pinFinder, retractionFinder, versionFinder,
        _scalaVersion)

  }

  /** Builds a `Finders` from the current sbt `State`, sourcing every finder from the project's `ThisBuild`-scoped
    * settings and resolvers.
    *
    * @param scalaV
    *   the Scala version the resolver pipeline is bound to. Main-build commands typically pass `scalaVersion.value`;
    *   meta-build commands (sbt-plugin / sbt / scalafmt updates) pass a fixed `"2.12.0"` or `"2.13.0"` because they
    *   resolve against artifacts published under those Scala binary versions.
    */
  def fromState(state: State, scalaV: String): Finders = {
    implicit val logger: Logger = state.log

    val project = Project.extract(state)

    implicit val configCache: ConfigCache =
      ConfigCache(project.get(ThisBuild / baseDirectory) / "target" / "sbt-dependencies" / "config-cache")

    val repositories: Seq[MavenRepository] = {
      val sbtRepositories =
        project.get(ThisBuild / resolvers).collect { case repo: MavenRepo => MavenRepository(repo.root) }

      val coursierRepositories =
        Resolve.defaultRepositories.collect { case m: MavenRepository => m }

      (coursierRepositories ++ sbtRepositories).toList.distinctBy(_.root)
    }

    val cooldownFinder: CooldownFinder =
      CooldownFinder.fromUrls(project.get(ThisBuild / Keys.dependencyCooldowns))

    val ageChecker: AgeChecker =
      AgeChecker(repositories, timeoutSeconds = project.get(ThisBuild / Keys.dependencyResolverTimeout)).cached

    val ignoreFinder: IgnoreFinder =
      IgnoreFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateIgnores))

    val migrationFinder: MigrationFinder =
      MigrationFinder.fromUrls(project.get(ThisBuild / Keys.dependencyMigrations))

    val pinFinder: PinFinder =
      PinFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdatePins))

    val retractionFinder: RetractionFinder =
      RetractionFinder.fromUrls(project.get(ThisBuild / Keys.dependencyUpdateRetractions))

    val versionFinder: VersionFinder =
      VersionFinder
        .fromCoursier(scalaV, project.get(ThisBuild / Keys.dependencyResolverTimeout), repositories)
        .cached
        .ignoringVersions(ignoreFinder)
        .excludingRetracted(retractionFinder)
        .pinningVersions(pinFinder)

    val scalaVersion: ScalaVersion = ScalaVersion(scalaV)

    new Impl()(cooldownFinder, ageChecker, ignoreFinder, migrationFinder, pinFinder, retractionFinder, versionFinder,
      scalaVersion)
  }

  val noop: Finders = new Impl()(
    cooldownFinder = CooldownFinder.empty,
    ageChecker = (_, _, _, _) => Right(()),
    ignoreFinder = IgnoreFinder.empty,
    migrationFinder = _ => None,
    pinFinder = (_, _, _) => true,
    retractionFinder = RetractionFinder.noop,
    versionFinder = (_, _, _, _) => Nil,
    scalaVersion = ScalaVersion("2.13.16")
  )

}
