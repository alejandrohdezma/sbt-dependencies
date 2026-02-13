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

package com.alejandrohdezma.sbt

import scala.util.Failure
import scala.util.Try

import com.typesafe.config.Config

package object dependencies {

  implicit class ConfigOps(config: Config) {

    /** Gets a string value from the config or `None` if the path does not exist.
      *
      * @throws com.typesafe.config.ConfigException.WrongType
      *   if the path is not a string.
      */
    def get(path: String): Option[String] =
      if (config.hasPath(path)) Some(config.getString(path)) else None

  }

  implicit class TryOps[A](tryA: Try[A]) {

    /** Runs a side effect When the value inside the `Try` is a failure. */
    def onError(f: PartialFunction[Throwable, Unit]): Try[A] = tryA.recoverWith {
      case e if f.isDefinedAt(e) =>
        f(e)
        Failure(e)
    }

  }

}
