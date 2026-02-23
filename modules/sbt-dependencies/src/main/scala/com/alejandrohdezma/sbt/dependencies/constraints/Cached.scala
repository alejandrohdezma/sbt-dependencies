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
import scala.reflect.ClassTag

import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies._
import com.typesafe.config.Config

abstract class Cached[A: ClassTag] {

  private val cache = new ConcurrentHashMap[URL, List[A]]() // scalafix:ok

  val name = implicitly[ClassTag[A]].runtimeClass.getSimpleName // scalafix:ok

  def configToValue(config: Config): Either[String, List[A]]

  /** Loads values from a list of URLs.
    *
    * Supports both `https://` and `file://` URLs. Each URL is fetched and parsed as HOCON.
    *
    * @param urls
    *   The list of URLs to load values from
    * @return
    *   Combined list of all values from all URLs
    */
  def loadFromUrls(urls: List[URL])(implicit logger: Logger, configCache: ConfigCache): List[A] =
    urls.flatMap(cache.computeIfAbsent(_, loadFromUrl))

  def loadFromUrl(url: URL)(implicit logger: Logger, configCache: ConfigCache): List[A] =
    configCache
      .get(url)
      .map { config =>
        logger.debug(s"↻ Loading $name from $CYAN$url$RESET")

        configToValue(config)
          .onLeft(e => logger.warn(s"⚠ Skipping malformed $name from $CYAN$url$RESET: $e"))
          .getOrElse(Nil)
      }
      .onLeft(logger.warn(_))
      .getOrElse(Nil)

}
