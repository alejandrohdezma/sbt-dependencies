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

import java.net.URI

import scala.concurrent.duration.FiniteDuration

import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.constraints.ConfigCache
import com.alejandrohdezma.sbt.dependencies.constraints.CooldownDefault
import com.alejandrohdezma.sbt.dependencies.constraints.CooldownEntry
import com.alejandrohdezma.sbt.dependencies.constraints.CooldownOverride

/** Abstraction for resolving the cooldown that applies to a dependency.
  *
  * Cooldown semantics (mirroring Scala Steward PR #3881): the first override whose pattern matches `(organization,
  * name, version)` wins; if none match, the build-wide default applies; otherwise no cooldown is enforced.
  */
trait CooldownFinder {

  /** @return the cooldown to apply for `(organization, name, version)`, or `None` if no rule matches. */
  def cooldownFor(organization: String, name: String, version: String): Option[FiniteDuration]

}

object CooldownFinder {

  /** A finder that never returns a cooldown — used when the feature isn't configured. */
  val empty: CooldownFinder = (_, _, _) => None

  /** Loads cooldown entries from the given URLs and returns a finder backed by them.
    *
    * If multiple URLs contribute a `CooldownDefault`, the first one wins — same precedence as "first override wins."
    */
  def fromUrls(urls: List[URI])(implicit logger: Logger, configCache: ConfigCache): CooldownFinder = {
    val entries = CooldownEntry.loadFromUrls(urls)

    val overrides = entries.collect { case o: CooldownOverride => o }
    val default   = entries.collectFirst { case CooldownDefault(d) => d }

    (organization, name, version) =>
      overrides
        .find(_.matches(organization, name, version))
        .map(_.minimumAge)
        .orElse(default)
  }

}
