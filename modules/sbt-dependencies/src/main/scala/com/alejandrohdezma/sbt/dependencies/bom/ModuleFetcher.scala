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

import sbt._
import sbt.librarymanagement.DependencyResolution
import sbt.librarymanagement.UnresolvedWarningConfiguration
import sbt.librarymanagement.UpdateConfiguration

/** Fetches a module's artifacts. This is the seam between the pom model and sbt's dependency resolution, so tests can
  * substitute a stub serving pre-baked pom files.
  */
private[dependencies] trait ModuleFetcher {

  /** The artifacts (with their files) `moduleID` resolves to. */
  def fetch(moduleID: ModuleID): Vector[(Artifact, File)]

}

private[dependencies] object ModuleFetcher {

  /** A fetcher backed by an ivy `DependencyResolution`, retrieving modules as pom-only, intransitive artifacts. */
  def fromIvyDependencyResolution(resolution: DependencyResolution)(implicit log: Logger): ModuleFetcher = moduleID => {
    val module = moduleID.pomOnly().intransitive()

    val updateConfig = UpdateConfiguration()

    val unresolvedWarningConfig = UnresolvedWarningConfiguration()

    resolution
      .update(resolution.wrapDependencyInModule(module), updateConfig, unresolvedWarningConfig, log)
      .fold(w => sys.error(s"Failed to resolve $moduleID: ${w.resolveException.getMessage}"), identity)
      .toVector
      .map { case (_, _, artifact, file) => (artifact, file) }
  }

}
