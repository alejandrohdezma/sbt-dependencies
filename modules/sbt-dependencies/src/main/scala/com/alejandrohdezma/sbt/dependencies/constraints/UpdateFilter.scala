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

import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Eq._

/** Represents a filter for updating dependencies. */
sealed trait UpdateFilter {

  def show: String = this match {
    case UpdateFilter.All                        => "all"
    case UpdateFilter.ByOrg(org)                 => org
    case UpdateFilter.ByArtifact(art)            => art
    case UpdateFilter.ByOrgAndArtifact(org, art) => s"$org:$art"
  }

  /** Checks if a dependency matches the filter. */
  def matches(dependency: Dependency): Boolean = this match {
    case UpdateFilter.All                        => true
    case UpdateFilter.ByOrg(org)                 => dependency.organization === org
    case UpdateFilter.ByArtifact(art)            => dependency.name === art
    case UpdateFilter.ByOrgAndArtifact(org, art) => dependency.organization === org && dependency.name === art
  }

}

object UpdateFilter {

  /** Matches all dependencies. */
  case object All extends UpdateFilter

  /** Matches dependencies by organization. */
  final case class ByOrg(organization: String) extends UpdateFilter

  /** Matches dependencies by artifact name. */
  final case class ByArtifact(artifact: String) extends UpdateFilter

  /** Matches dependencies by organization and artifact name. */
  final case class ByOrgAndArtifact(organization: String, artifact: String) extends UpdateFilter

}
