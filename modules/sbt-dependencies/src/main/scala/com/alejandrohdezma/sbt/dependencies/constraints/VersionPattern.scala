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

package com.alejandrohdezma.sbt.dependencies.constraints

import com.alejandrohdezma.sbt.dependencies.config._
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.typesafe.config.ConfigValueType

/** Pattern for matching dependency versions.
  *
  * All defined conditions are AND-ed together. An empty pattern (all fields None) matches every version.
  *
  * @param prefix
  *   Matches versions that start with this string.
  * @param suffix
  *   Matches versions that end with this string.
  * @param exact
  *   Matches versions that are exactly this string.
  * @param contains
  *   Matches versions that contain this substring.
  */
final case class VersionPattern(
    prefix: Option[String] = None,
    suffix: Option[String] = None,
    exact: Option[String] = None,
    contains: Option[String] = None
) {

  /** Checks if the given version string matches all defined conditions. */
  def matches(version: String): Boolean =
    prefix.forall(version.startsWith) &&
      suffix.forall(version.endsWith) &&
      exact.forall(_ === version) &&
      contains.forall(version.contains)

}

object VersionPattern {

  /** Decodes an optional version pattern from a HOCON config entry.
    *
    * Supports three shapes:
    *   - Missing path → `None`
    *   - String value → `Some(VersionPattern(prefix = ...))` (prefix shorthand)
    *   - Object value → `Some(VersionPattern(prefix, suffix, exact, contains))`
    */
  implicit val configDecoder: ConfigDecoder[Option[VersionPattern]] = {
    case (config, path) if !config.hasPath(path) => Right(None)
    case (config, path) =>
      config.getValue(path).valueType() match {
        case ConfigValueType.STRING =>
          Right(Some(VersionPattern(prefix = Some(config.getString(path)))))

        case ConfigValueType.OBJECT =>
          val obj = config.getConfig(path)
          for {
            prefix   <- obj.as[Option[String]]("prefix")
            suffix   <- obj.as[Option[String]]("suffix")
            exact    <- obj.as[Option[String]]("exact")
            contains <- obj.as[Option[String]]("contains")
          } yield Some(VersionPattern(prefix, suffix, exact, contains))

        case other =>
          Left(s"has unsupported version type: $other")
      }
  }

}
