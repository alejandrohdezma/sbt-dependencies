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

import java.nio.file.Files

import scala.Console._
import scala.jdk.CollectionConverters._
import scala.sys.process._
import scala.util.Try
import scala.util.Using

import sbt._
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Dependency.Version
import com.alejandrohdezma.sbt.dependencies.Eq._

/** Utilities for managing Scalafmt version in `.scalafmt.conf`. */
object Scalafmt {

  /** Updates all `.scalafmt.conf` files found recursively under `baseDir` to the latest version.
    *
    * @return
    *   `true` if any version was updated, `false` otherwise.
    */
  def updateVersion(baseDir: File)(implicit
      versionFinder: VersionFinder,
      migrationFinder: MigrationFinder,
      retractionFinder: RetractionFinder,
      logger: Logger
  ): Boolean =
    Using.resource(Files.walk(baseDir.toPath)) { stream =>
      val file = Path(".scalafmt.conf").asPath

      val files =
        try stream.iterator().asScala.filter(_.getFileName === file).map(_.toFile).toList
        finally stream.close()

      if (files.isEmpty) {
        logger.warn(s"No .scalafmt.conf files found under $baseDir, skipping scalafmt version update")
        false
      } else {
        val filtered = filterGitIgnored(files, baseDir)

        filtered.foldLeft(false) { (acc, file) =>
          updateVersionInFile(file, baseDir) || acc
        }
      }
    }

  private def updateVersionInFile(file: File, baseDir: File)(implicit
      versionFinder: VersionFinder,
      migrationFinder: MigrationFinder,
      retractionFinder: RetractionFinder,
      logger: Logger
  ): Boolean = {
    val relativePath = baseDir.toPath.relativize(file.toPath)
    val content      = IO.read(file)

    // Regex to match: version = "3.8.0" or version = 3.8.0
    // Captures: (prefix with quotes or not)(version)(suffix with quotes or not)
    val versionRegex = """(?m)^(\s*version\s*=\s*"?)([^"\s\n]+)("?\s*)$""".r

    versionRegex.findFirstMatchIn(content).map(_.group(2)) match {
      case Some(Version.Numeric(current)) =>
        val dependency = Dependency.scalafmt(current)

        val latest = dependency.findLatestVersion.version

        if (latest === current) {
          retractionFinder.warnIfRetracted(dependency)
          logger.info(s" ↳ $GREEN✓$RESET $GREEN$relativePath: ${current.toVersionString}$RESET")
          false
        } else {
          logger.info(
            s" ↳ $YELLOW⬆$RESET $YELLOW$relativePath: ${current.toVersionString}$RESET -> $CYAN${latest.toVersionString}$RESET"
          )

          val newContent =
            versionRegex.replaceAllIn(content, m => s"${m.group(1)}${latest.toVersionString}${m.group(3)}")

          IO.write(file, newContent)

          true
        }

      case Some(version) =>
        logger.warn(s"Invalid version found in $relativePath: $version")
        false

      case None =>
        logger.warn(s"No version found in $relativePath")
        false
    }
  }

  private def filterGitIgnored(files: List[File], baseDir: File)(implicit logger: Logger): List[File] =
    Try {
      val ignored  = scala.collection.mutable.Set.empty[String]
      val pLogger  = ProcessLogger(line => ignored += line.trim, _ => ())
      val paths    = files.map(_.getAbsolutePath)
      val exitCode = Process("git" +: "check-ignore" +: paths, baseDir).!(pLogger)

      if (exitCode === 128) files
      else {
        val (skip, keep) = files.partition(f => ignored.contains(f.getAbsolutePath))

        skip.foreach { f =>
          val relativePath = baseDir.toPath.relativize(f.toPath)
          logger.info(s" ↳ $YELLOW»$RESET $relativePath (git-ignored)")
        }

        keep
      }
    }.getOrElse(files)

}
