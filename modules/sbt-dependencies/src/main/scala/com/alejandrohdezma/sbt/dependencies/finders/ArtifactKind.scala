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

import com.alejandrohdezma.sbt.dependencies.model.Dependency

/** The shape under which an artifact is published — controls how `VersionFinder` queries Maven for available versions.
  *
  * The `compiler-plugin` configuration token does not, by itself, imply a cross-version shape: it only tells SBT to
  * apply the `plugin->default(compile)` configuration. Per-patch publication (`name_2.13.16`) and per-binary
  * publication (`name_2.13`) are both common for compiler plugins, and the user picks one via the `cross-version`
  * annotation.
  */
sealed trait ArtifactKind

object ArtifactKind {

  /** No Scala-version suffix in the module name (e.g. `name`). Maps to `CrossVersion.disabled`. */
  case object Java extends ArtifactKind

  /** Binary Scala version suffix (e.g. `name_2.13`). Maps to `CrossVersion.binary` — SBT's `%%` default. */
  case object Cross extends ArtifactKind

  /** Full Scala version suffix (e.g. `name_2.13.16`). Maps to `CrossVersion.full` / `CrossVersion.patch`. */
  case object CrossFull extends ArtifactKind

  /** SBT plugin — queried with sbt's plugin module shape (extra attributes + binary Scala/sbt versions). */
  case object SbtPlugin extends ArtifactKind

  /** Derives an `ArtifactKind` from a [[com.alejandrohdezma.sbt.dependencies.model.Dependency]] alone (no annotation
    * context). Compiler plugins fall into the `Cross` / `Java` buckets just like any other dependency — for
    * per-patch-published plugins like `kind-projector` the user must opt into `CrossFull` via the `cross-version`
    * annotation.
    */
  def fromDependency(dep: Dependency): ArtifactKind = dep.configuration match {
    case "sbt-plugin"     => SbtPlugin
    case _ if dep.isCross => Cross
    case _                => Java
  }

}
