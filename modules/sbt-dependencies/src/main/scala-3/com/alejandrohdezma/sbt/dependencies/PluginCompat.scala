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

import sbt.Keys.update
import sbt.internal.librarymanagement.IvySbt
import sbt.internal.librarymanagement.ivy.InlineIvyConfiguration
import sbt.internal.librarymanagement.ivy.IvyCredentials
import sbt.util.Logger
import sbt.{Keys as _, *}

/** Constants and settings that depend on the sbt axis this plugin is built for. This is the sbt 2.x (Scala 3) side. */
private[dependencies] object PluginCompat {

  type IvyPaths = sbt.librarymanagement.IvyPaths

  type UpdateOptions = sbt.internal.librarymanagement.ivy.UpdateOptions

  /** An `IvySbt` over the given resolvers, from which pom files can be downloaded directly. Ivy (not coursier) because
    * coursier does not report pom-type artifacts, which `BomReader` needs. On sbt 2 the ivy configuration moved to
    * `sbt.internal`.
    */
  def ivySbt(
      resolvers: Seq[Resolver],
      updateOptions: UpdateOptions,
      ivyPaths: IvyPaths,
      log: Logger
  ): IvySbt =
    new IvySbt(
      InlineIvyConfiguration()
        .withResolvers(resolvers.toVector)
        .withUpdateOptions(updateOptions)
        .withPaths(ivyPaths)
        .withLog(log)
    )

  /** Registers credentials from the given file into Ivy's global store. sbt 2 offers a combined load-and-register. */
  def registerCredentials(file: File, log: Logger): Unit =
    IvyCredentials.add(file, log)

  /** Task overrides whose values sbt 2 cannot cache (no `JsonFormat`), opted out explicitly with `Def.uncached`. */
  def uncachedTaskSettings: Seq[Def.Setting[?]] = Seq(
    update                      := Def.uncached(Tasks.updateWithChecks.value),
    Keys.allProjectDependencies := Def.uncached(Tasks.allProjectDependencies.value)
  )

  /** Maven artifact-name suffix of plugins targeting the running sbt. */
  val sbtPluginArtifactSuffix: String = "_sbt2_3"

  /** sbt 2 plugins are published Maven-style only, so there is no Ivy-attributes variant to query. */
  val sbtPluginAttributes: Option[Map[String, String]] = None

  /** Scala version assumed for the meta-build when resolving `sbt-build` group dependencies. */
  val metaBuildScalaVersion: String = "3.8.4"

  /** Available version strings of a coursier version listing. The sbt 1 build uses coursier 2.1.24, where `available` is
    * the only spelling; the sbt 2 build uses 2.1.26, where it is deprecated in favor of `available0`.
    */
  def availableVersions(versions: coursier.core.Versions): List[String] =
    versions.available0.map(_.repr)

  /** Whether `version` sorts before `other` under coursier's version ordering. The sbt 1 build uses coursier 2.1.24, where
    * the ordering lives in `coursier.core.Version`; the sbt 2 build uses 2.1.26, where it moved to `coursier.version.Version`.
    */
  def isLowerVersion(version: String, other: String): Boolean = {
    import coursier.version.Version

    Version(version) < Version(other)
  }

  /** A coursier version lister whose cache never expires changing metadata. On Scala 3, coursier's data types are case
    * classes without `with*` setters.
    */
  def versionsWithoutTtl(): coursier.Versions[coursier.util.Task] =
    coursier.Versions().copy(cache = coursier.cache.FileCache().copy(ttl = None))

  /** A `HEAD` request builder for `url` that authenticates with `authentication` or any of `credentials`. */
  def headConnection(
      url: String,
      authentication: Option[coursier.core.Authentication],
      credentials: Seq[coursier.credentials.DirectCredentials]
  ): coursier.cache.ConnectionBuilder =
    coursier.cache.ConnectionBuilder(
      url = url,
      authentication = authentication,
      followHttpToHttpsRedirections = true,
      autoCredentials = credentials,
      method = "HEAD"
    )

}
