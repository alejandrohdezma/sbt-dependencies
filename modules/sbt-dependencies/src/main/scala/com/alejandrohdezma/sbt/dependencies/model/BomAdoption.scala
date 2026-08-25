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

package com.alejandrohdezma.sbt.dependencies.model

import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version.Numeric

/** The outcome of offering a dependency line the BOM-managed marker (`*`). `dependency` is always the line to write
  * back — rewritten for [[BomAdoption.Adopted]], untouched otherwise.
  */
sealed trait BomAdoption { def dependency: Dependency }

object BomAdoption {

  /** The line was rewritten to `*`: `from` is the original line and `pinned` the version the BOM resolves it to. */
  final case class Adopted(dependency: Dependency, from: Dependency, pinned: Numeric) extends BomAdoption

  /** A visible BOM pins the line but safe mode left it as is (an explicit marker or a version variable is treated as an
    * opt-out); `pinned` is the version the BOM would resolve it to.
    */
  final case class Skipped(dependency: Dependency, reason: String, pinned: Numeric) extends BomAdoption

  /** Nothing to do: already `*`, `*` would be illegal on the line, or no visible BOM pins it. */
  final case class Unchanged(dependency: Dependency) extends BomAdoption

}
