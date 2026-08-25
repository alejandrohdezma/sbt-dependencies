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

package com.alejandrohdezma.sbt.dependencies.io

import com.alejandrohdezma.sbt.dependencies.model.BomAdoption
import com.alejandrohdezma.sbt.dependencies.model.Group

/** Renders what `useBomManagedVersions` did to a group as GitHub-flavoured markdown bullets, one line per dependency,
  * for the report the GitHub Action appends to the pull request. Each bullet starts with the group name so the action
  * can concatenate every group's file and sort the result deterministically.
  */
object BomAdoptionReport {

  /** The bullets for `group`, or `None` when nothing was adopted or skipped (so the caller drops the group's file). */
  def render(group: Group, adoptions: List[BomAdoption]): Option[String] = {
    val lines = adoptions.collect {
      case BomAdoption.Adopted(_, from, pinned) =>
        s"- `$group`: `${from.toLine}` → `*` (resolves to `${pinned.toVersionString}`)"
      case BomAdoption.Skipped(dependency, reason, pinned) =>
        s"- `$group`: `${dependency.toLine}` left as is — $reason (BOM pins `${pinned.toVersionString}`)"
    }

    if (lines.isEmpty) None else Some(lines.mkString("", "\n", "\n"))
  }

}
