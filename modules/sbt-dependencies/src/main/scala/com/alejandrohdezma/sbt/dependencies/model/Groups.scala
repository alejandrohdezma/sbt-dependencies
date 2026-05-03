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

/** Reserved group names in `dependencies.conf` and the canonical ordering used when writing the file. */
object Groups {

  /** Group whose dependencies become the meta-build (`project/project/plugins.sbt`). */
  val `sbt-build`: String = "sbt-build"

  /** Group whose `scala-version[s]` / `java-version` / `dependencies` apply to every non-meta project as defaults. */
  val `common-settings`: String = "common-settings"

  /** Group names that cannot be used as SBT project names. */
  val Reserved: Set[String] = Set(`sbt-build`, `common-settings`)

  /** Ordering used when serialising groups to HOCON: `sbt-build` first, then `common-settings`, then alphabetical. */
  val ordering: Ordering[String] = Ordering.by {
    case `sbt-build`       => (0, "")
    case `common-settings` => (1, "")
    case other          => (2, other)
  }

}
