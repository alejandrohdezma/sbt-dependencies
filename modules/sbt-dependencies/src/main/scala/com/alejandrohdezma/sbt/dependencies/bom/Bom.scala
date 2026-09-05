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

package com.alejandrohdezma.sbt.dependencies.bom

import sbt.Keys._
import sbt._
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Settings
import com.alejandrohdezma.sbt.dependencies.finders.Utils
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.DependencyOps._
import com.alejandrohdezma.sbt.dependencies.model.Eq._

/** A Maven BOM read for programmatic consumption from build code: its flattened managed dependencies (`pins`) and the
  * Scala binary version they were resolved for. Use [[Bom.read]] to obtain one, then look up a managed version with
  * [[version]] / the `%` syntax.
  *
  * This is the consumption counterpart to the declarative `:bom` + `*` workflow in `dependencies.conf`: same resolution
  * (via [[BomReader]]), exposed as an sbt-code API that works on both sbt 1.x and 2.x.
  *
  * @example
  *   {{{
  * lazy val myBom = Bom.read("com.example" %% "my-bom" % "1.0.0")
  *
  * lazy val app = project
  *   .settings(bom := myBom.value)
  *   .settings(libraryDependencies += "com.example" %% "my-lib" % bom.value)
  *   }}}
  */
final class Bom(pins: Seq[ModuleID], scalaBinaryVersion: String)(implicit logger: Logger) {

  /** The version the BOM pins for `artifact`, failing the build when the BOM doesn't manage it. Cross-compiled (`%%`)
    * artifacts are matched against the Scala-suffixed entry.
    */
  def version(artifact: OrganizationArtifactName): String =
    Dependency.fromModuleID(artifact % "*").get.resolveBom(pins, scalaBinaryVersion) match {
      case dep @ Dependency(_, _, Dependency.Version.Bom(None), _, _, _, _, _, _) =>
        Utils.fail(s"${dep.toLine} is not managed by the BOM")
      case dep => dep.version.toVersionString
    }

}

object Bom {

  /** Reads and flattens `bom` against the project's `update` resolvers (credentials from sbt's conventional files). */
  def read(bom: ModuleID): Def.Initialize[Bom] = Def.setting {
    val scalaV = (update / scalaBinaryVersion).value

    implicit val logger: Logger         = sLog.value
    implicit val fetcher: ModuleFetcher = Settings.bomFetcher.value

    new Bom(BomReader.read(bom, scalaV), scalaV)
  }

  /** Enables `"org" %% "name" % bom`, resolving the artifact's version from the BOM. */
  implicit class BomSyntax(private val value: OrganizationArtifactName) extends AnyVal {

    def %(bom: Bom): ModuleID = value % bom.version(value)

  }

  /** Keeps the first pin for each `organization:name`, preserving order. */
  private[dependencies] def dedupeByModule(pins: Seq[ModuleID]): Seq[ModuleID] =
    pins.foldLeft(Vector.empty[ModuleID]) { (acc, module) =>
      if (acc.exists(m => m.organization === module.organization && m.name === module.name)) acc else acc :+ module
    }

}
