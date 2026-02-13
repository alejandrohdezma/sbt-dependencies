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
    def onError(f: Throwable => Unit): Try[A] = tryA.recoverWith { case e =>
      f(e)
      Failure(e)
    }

  }

}
