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

package com.alejandrohdezma.sbt.dependencies.config

import scala.jdk.CollectionConverters._

import com.typesafe.config.Config

/** Typeclass for decoding values from a HOCON `Config`.
  *
  * For primitive types (`String`, `Option[String]`, `List[String]`, `List[Config]`), `path` is the HOCON key to read
  * from `config`.
  *
  * For domain types (`UpdateIgnore`, `RetractedArtifact`, etc.), `config` is the sub-config object representing one
  * entry and `path` is the error context string (e.g., `"entry at index 0"`).
  */
trait ConfigDecoder[A] {

  def decode(config: Config, path: String): Either[String, A]

  def map[B](f: A => B): ConfigDecoder[B] = { (config, path) => decode(config, path).map(f) }

}

object ConfigDecoder {

  def apply[A](implicit decoder: ConfigDecoder[A]): ConfigDecoder[A] = decoder

  /** Decodes a required string value. Returns `Left` if the path is missing. */
  implicit val string: ConfigDecoder[String] = { (config, path) =>
    if (config.hasPath(path)) Right(config.getString(path))
    else Left(s"must have a '$path'")
  }

  /** Decodes an optional string value. Returns `Right(None)` if the path is missing. */
  implicit val optionString: ConfigDecoder[Option[String]] = { (config, path) =>
    Right(if (config.hasPath(path)) Some(config.getString(path)) else None)
  }

  /** Decodes a string list. Returns `Right(Nil)` if the path is missing. */
  implicit val stringList: ConfigDecoder[List[String]] = { (config, path) =>
    Right(if (config.hasPath(path)) config.getStringList(path).asScala.toList else Nil)
  }

  def configList[A](f: Config => Either[String, A]): ConfigDecoder[List[A]] = {
    case (config, path) if !config.hasPath(path) => Left(s"must have a '$path' array")
    case (config, path)                          => foldConfigList(config, path, f)
  }

  def optionalConfigList[A](f: Config => Either[String, A]): ConfigDecoder[List[A]] = {
    case (config, path) if !config.hasPath(path) => Right(Nil)
    case (config, path)                          => foldConfigList(config, path, f)
  }

  private def foldConfigList[A](config: Config, path: String, f: Config => Either[String, A]): Either[String, List[A]] =
    config.getConfigList(path).asScala.toList.zipWithIndex.foldLeft[Either[String, List[A]]](Right(Nil)) {
      case (Right(acc), (change, index)) =>
        f(change) match {
          case Right(value) => Right(acc :+ value)
          case Left(err)    => Left(s"entry at index $index: $err")
        }
      case (Left(err), _) => Left(err)
    }

}
