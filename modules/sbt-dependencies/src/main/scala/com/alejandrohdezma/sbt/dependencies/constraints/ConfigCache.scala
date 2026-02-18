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

import java.net.URL
import java.util.concurrent.ConcurrentHashMap

import scala.Console._

import sbt.util.Logger

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

/** Shared cache for `ConfigFactory.parseURL` results.
  *
  * Multiple constraint types (migrations, pins, ignores, retractions) may reference the same URL. This cache ensures
  * each URL is fetched over HTTP only once per JVM session, regardless of how many constraint types read from it.
  */
object ConfigCache {

  private val cache = new ConcurrentHashMap[URL, Config]()

  /** Returns the parsed `Config` for the given URL, fetching it at most once. */
  def get(url: URL)(implicit logger: Logger): Config =
    cache.computeIfAbsent(
      url,
      { url =>
        logger.info(s"↻ Loading config from $CYAN$url$RESET")
        ConfigFactory.parseURL(url)
      }
    )

}
