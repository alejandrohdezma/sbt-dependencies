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

package com.alejandrohdezma.sbt.dependencies

import scala.Console._

import sbt._
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Dependency.Version
import com.alejandrohdezma.sbt.dependencies.Eq._

/** Utilities for managing Scalafmt version in `.scalafmt.conf`. */
object Scalafmt {

  /** Updates `.scalafmt.conf` to the latest version.
    *
    * @return
    *   `true` if the version was updated, `false` otherwise.
    */
  def updateVersion(baseDir: File)(implicit
      versionFinder: VersionFinder,
      migrationFinder: MigrationFinder,
      logger: Logger
  ): Boolean = {
    val file = baseDir / ".scalafmt.conf"

    if (!file.exists()) {
      logger.warn(s"$file not found, skipping scalafmt version update")
      false
    } else {
      val content = IO.read(file)

      // Regex to match: version = "3.8.0" or version = 3.8.0
      // Captures: (prefix with quotes or not)(version)(suffix with quotes or not)
      val versionRegex = """(?m)^(\s*version\s*=\s*"?)([^"\s\n]+)("?\s*)$""".r

      versionRegex.findFirstMatchIn(content).map(_.group(2)) match {
        case Some(Version.Numeric(current)) =>
          val latest = Dependency.scalafmt(current).findLatestVersion.version

          if (latest === current) {
            logger.info(s" ↳ ✅ $GREEN${current.toVersionString}$RESET")
            false
          } else {
            logger.info(s" ↳ ⬆️ $YELLOW${current.toVersionString}$RESET -> $CYAN${latest.toVersionString}$RESET")

            val newContent =
              versionRegex.replaceAllIn(content, m => s"${m.group(1)}${latest.toVersionString}${m.group(3)}")

            IO.write(file, newContent)

            true
          }

        case Some(version) =>
          logger.warn(s"Invalid version found in $file: $version")
          false

        case None =>
          logger.warn(s"No version found in $file")
          false
      }
    }
  }

}
