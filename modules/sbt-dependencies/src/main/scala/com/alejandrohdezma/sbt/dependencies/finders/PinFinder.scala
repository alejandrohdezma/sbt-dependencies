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

import com.alejandrohdezma.sbt.dependencies.constraints.UpdatePin

/** Abstraction for checking if a dependency version is allowed by pin constraints.
  *
  * A version is allowed if no pin matches the artifact, or if the version matches all matching pins' version patterns.
  */
trait PinFinder {

  /** Checks if the given dependency version is allowed by pin constraints.
    *
    * @param organization
    *   The organization/groupId.
    * @param name
    *   The artifact name.
    * @param version
    *   The version string to check.
    * @return
    *   true if this version is allowed (passes all matching pin constraints).
    */
  def isAllowed(organization: String, name: String, version: String): Boolean

}

object PinFinder {

  /** Creates a PinFinder that loads pin patterns from the given URLs. */
  def fromUrls(urls: List[URL])(implicit logger: Logger): PinFinder = {
    val pins = UpdatePin.loadFromUrls(urls)

    (organization, name, version) =>
      !pins.exists(p => p.matchesArtifact(organization, name) && !p.matchesVersion(version))
  }

}
