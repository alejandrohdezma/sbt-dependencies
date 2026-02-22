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

import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

import scala.Console._
import scala.util.Try

import sbt.IO
import sbt.util.Logger

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions

/** Shared cache for `ConfigFactory.parseURL` results.
  *
  * Multiple constraint types (migrations, pins, ignores, retractions) may reference the same URL. This cache ensures
  * each URL is fetched over HTTP only once, regardless of how many constraint types read from it.
  *
  * Two cache layers:
  *   - In-memory `ConcurrentHashMap` (fast, lost on `reload`)
  *   - File-based in `cacheDir` (survives `reload`, cleared by `sbt clean`)
  */
final case class ConfigCache(cacheDir: File) {

  private val cache = new ConcurrentHashMap[URL, Config]()

  /** Returns the parsed `Config` for the given URL, fetching it at most once. */
  def get(url: URL)(implicit logger: Logger): Either[String, Config] = Try {
    cache.computeIfAbsent(
      url,
      { url =>
        val cached = fileFor(url)

        if (cached.exists()) ConfigFactory.parseFile(cached)
        else {
          logger.info(s"↻ Loading config from $CYAN$url$RESET")

          val config = ConfigFactory.parseURL(url)

          cached.getParentFile.mkdirs()
          IO.write(cached, config.root().render(ConfigRenderOptions.concise().setJson(false)))

          config
        }
      }
    )
  }.fold(e => Left(s"Failed to parse config from $url: ${e.getMessage}"), Right(_))

  private def fileFor(url: URL): File = {
    val hash = MessageDigest
      .getInstance("SHA-256")
      .digest(url.toExternalForm().getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString

    new File(cacheDir, s"$hash.conf")
  }

}
