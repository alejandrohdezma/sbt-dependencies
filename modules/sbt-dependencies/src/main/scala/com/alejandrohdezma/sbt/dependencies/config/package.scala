package com.alejandrohdezma.sbt.dependencies

import com.typesafe.config.Config

package object config {

  implicit class ConfigOps(config: Config) {

    /** Decodes a value at `path` using the implicit `ConfigDecoder`. */
    def as[A](path: String)(implicit decoder: ConfigDecoder[A]): Either[String, A] =
      decoder.decode(config, path)

    /** Decodes a required non-empty `List[String]`, failing if the path is missing. */
    def asNonEmptyList(path: String): Either[String, List[String]] =
      ConfigDecoder.stringList.decode(config, path).flatMap {
        case Nil  => Left(s"must have '$path'")
        case list => Right(list)
      }

  }

}
