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

import scala.annotation.tailrec
import scala.util.matching.Regex

import sbt._

import com.alejandrohdezma.sbt.dependencies.model.Eq
import com.alejandrohdezma.sbt.dependencies.model.Eq._

/** A `groupId:artifactId:version` coordinate; `toString` renders that Maven form (used for cycle detection). */
private[bom] case class Coords(group: String, artifact: String, version: String) {

  /** The `groupId:artifactId` module coordinate. */
  val module = s"$group:$artifact"

  override def toString: String = s"$module:$version"

  /** This coordinate as an sbt `ModuleID`. */
  def toModuleID: ModuleID = ModuleID(group, artifact, version)

  /** Expands Maven placeholders against `properties`, re-running until the string stabilises or `remaining` passes are
    * used up. `None` when a placeholder has no matching property or expansion never converges, so the caller drops it.
    *
    * @example
    *   {{{
    * interpolate("${jackson.version}", Map("jackson.version" -> "2.17.1")) // Some("2.17.1")
    * interpolate("${a}", Map("a" -> "${b}", "b" -> "1.0"))                 // Some("1.0") (expanded in two passes)
    * interpolate("2.17.1", Map.empty)                                      // Some("2.17.1") (nothing to expand)
    * interpolate("${undefined}", Map.empty)                                // None (no matching property)
    * interpolate("${a}", Map("a" -> "${a}"))                               // None (never converges)
    *   }}}
    */
  @tailrec
  private def interpolate(expression: String, properties: Map[String, String], remaining: Int = 10): Option[String] =
    if (!expression.contains("${")) Some(expression)
    else if (remaining === 0) None
    else {
      val next = Coords.Placeholder.replaceAllIn(
        expression,
        m => Regex.quoteReplacement(properties.getOrElse(m.group(1), m.matched))
      )
      if (next === expression) None else interpolate(next, properties, remaining - 1)
    }

  /** Interpolates Maven placeholders in all three fields against `properties`; `None` if any can't be resolved.
    *
    * @example
    *   {{{
    * Coords("org.typelevel", "cats-core_${scala.compat.version}", "${cats.version}")
    *   .resolve(Map("scala.compat.version" -> "2.13", "cats.version" -> "2.13.0"))
    * // Some(Coords("org.typelevel", "cats-core_2.13", "2.13.0"))
    *
    * Coords("org.typelevel", "cats-core", "${cats.version}").resolve(Map.empty)
    * // None (version can't be resolved)
    *   }}}
    */
  def resolve(properties: Map[String, String]): Option[Coords] =
    for {
      g <- interpolate(group, properties)
      a <- interpolate(artifact, properties)
      v <- interpolate(version, properties)
    } yield Coords(g, a, v)

}

private[bom] object Coords {

  /** The coordinate for `bom`, suffixing its artifact name with `scalaBinaryVersion` when `bom` is cross-versioned. */
  def apply(bom: ModuleID, scalaBinaryVersion: String): Coords = {
    val artifact = bom.crossVersion match {
      case _: sbt.librarymanagement.Disabled => bom.name
      case _                                 => s"${bom.name}_$scalaBinaryVersion"
    }

    Coords(bom.organization, artifact, bom.revision)
  }

  private val Placeholder: Regex = """\$\{([^}]+)\}""".r

  /** Coordinate equality, delegating to the rendered `group:artifact:version` form. */
  implicit val CoordsEq: Eq[Coords] = (a, b) => a.toString === b.toString

}
