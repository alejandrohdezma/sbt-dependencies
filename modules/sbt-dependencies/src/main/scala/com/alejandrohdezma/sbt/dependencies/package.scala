package com.alejandrohdezma.sbt

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

}
