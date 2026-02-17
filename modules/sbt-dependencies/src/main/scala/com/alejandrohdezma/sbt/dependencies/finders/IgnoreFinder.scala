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

import java.net.URL

import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.constraints.UpdateIgnore

/** Abstraction for checking if a dependency version should be ignored. */
trait IgnoreFinder {

  /** Checks if the given dependency version should be ignored.
    *
    * @param organization
    *   The organization/groupId.
    * @param name
    *   The artifact name.
    * @param version
    *   The version string to check.
    * @return
    *   true if this version should be excluded from update candidates.
    */
  def isIgnored(organization: String, name: String, version: String): Boolean

}

object IgnoreFinder {

  /** Creates an IgnoreFinder that loads ignore patterns from the given URLs. */
  def fromUrls(urls: List[URL])(implicit logger: Logger): IgnoreFinder = {
    val ignores = UpdateIgnore.loadFromUrls(urls)

    (organization, name, version) => ignores.exists(_.matches(organization, name, version))
  }

  /** An IgnoreFinder that never ignores any version. */
  val empty: IgnoreFinder = (_, _, _) => false

}
