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

import com.alejandrohdezma.sbt.dependencies.model.Eq._

sealed abstract class Group(val name: String) {

  override def toString: String = name

}

/** Reserved group names in `dependencies.conf` and the canonical ordering used when writing the file. */
object Group {

  /** Group whose dependencies become the meta-build (`project/project/plugins.sbt`). */
  object `sbt-build` extends Group("sbt-build")

  /** Group whose `scala-version[s]` / `java-version` / `dependencies` apply to every non-meta project as defaults. */
  object `common-settings` extends Group("common-settings")

  case class Custom(override val name: String) extends Group(name)

  /** Builds a `Group` from a raw name, canonicalising reserved names to their singletons. */
  def apply(name: String): Group = name match {
    case `sbt-build`.name       => `sbt-build`
    case `common-settings`.name => `common-settings`
    case other                  => Custom(other)
  }

  /** Group names that cannot be used as SBT project names. */
  val Reserved: Set[String] = Set(`sbt-build`.name, `common-settings`.name)

  /** Ordering used when serialising groups to HOCON: `sbt-build` first, then `common-settings`, then alphabetical. */
  implicit val ordering: Ordering[Group] = Ordering.by {
    case `sbt-build`       => (0, "")
    case `common-settings` => (1, "")
    case Custom(other)     => (2, other)
  }

  implicit val GroupEq: Eq[Group] = (a, b) => a.name === b.name

}
