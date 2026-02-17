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

import com.alejandrohdezma.sbt.dependencies.model.Eq._

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
