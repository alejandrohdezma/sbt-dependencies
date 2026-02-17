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

package com.alejandrohdezma.sbt.dependencies

import java.net.URL

import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.constraints.ArtifactMigration
import com.alejandrohdezma.sbt.dependencies.model.Dependency

/** Abstraction for finding artifact migrations for a dependency. */
trait MigrationFinder {

  /** Finds a matching artifact migration for the given dependency, if any.
    *
    * @param dep
    *   The dependency to find a migration for.
    * @return
    *   An optional migration matching the dependency.
    */
  def findMigration(dep: Dependency): Option[ArtifactMigration]

}

object MigrationFinder {

  /** Creates a MigrationFinder that loads migrations from the given URLs. */
  def fromUrls(urls: List[URL])(implicit logger: Logger): MigrationFinder = {
    val migrations = ArtifactMigration.loadFromUrls(urls)

    dep => migrations.find(_.matches(dep))
  }

}
