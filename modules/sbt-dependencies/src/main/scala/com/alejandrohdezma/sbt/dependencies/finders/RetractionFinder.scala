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

import scala.Console._

import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.constraints.RetractedArtifact
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.string.box._

/** Abstraction for checking if a dependency version is retracted. */
trait RetractionFinder {

  /** Checks if the given dependency version is retracted.
    *
    * @param organization
    *   The organization/groupId.
    * @param name
    *   The artifact name.
    * @param version
    *   The version string to check.
    * @return
    *   true if this version is retracted and should be excluded from update candidates.
    */
  def isRetracted(organization: String, name: String, version: String): Boolean

  /** Logs a warning if the given dependency's current version is retracted.
    *
    * This should be called only when the dependency's version will remain unchanged in the file (e.g., it is already at
    * the latest version, pinned with an exact marker, or uses a variable version). If the dependency is being updated
    * to a non-retracted version, there is no need to warn.
    */
  def warnIfRetracted(dependency: Dependency): Unit

}

object RetractionFinder {

  /** Creates a RetractionFinder that loads retraction entries from the given URLs. */
  def fromUrls(urls: List[URL])(implicit logger: Logger): RetractionFinder = {
    val retractions = RetractedArtifact.loadFromUrls(urls)

    new RetractionFinder {

      override def isRetracted(organization: String, name: String, version: String): Boolean =
        retractions.exists(_.matches(organization, name, version))

      override def warnIfRetracted(dependency: Dependency): Unit = {
        val version = dependency.version.toVersionString

        retractions.find(_.matches(dependency.organization, dependency.name, version)).foreach { retraction =>
          logger.warn {
            s"""⚠ $CYAN${dependency.organization}:${dependency.name}$RESET $version is retracted.
               |  Reason: ${retraction.reason}
               |  Documentation: $CYAN${retraction.doc}$RESET
               |  You should consider using a different version.""".stripMargin.boxed
          }
        }
      }

    }
  }

}
