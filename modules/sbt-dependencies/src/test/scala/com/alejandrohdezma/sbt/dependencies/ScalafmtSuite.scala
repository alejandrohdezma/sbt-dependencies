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

import sbt._
import sbt.util.Level
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Dependency.Version

class ScalafmtSuite extends munit.FunSuite {

  implicit val migrationFinder: MigrationFinder = _ => None

  implicit val logger: Logger = new Logger {
    override def trace(t: => Throwable): Unit                      = ()
    override def success(message: => String): Unit                 = ()
    override def log(level: Level.Value, message: => String): Unit = ()
  }

  // --- updateVersion tests ---

  withScalafmtConf {
    """version = "3.7.0"
      |runner.dialect = scala213""".stripMargin
  }.test("updateVersion updates quoted version") { dir =>
    implicit val versionFinder: VersionFinder = mockVersionFinder("3.8.0")

    val updated = Scalafmt.updateVersion(dir)

    assert(updated, "Should return true when version was updated")

    val content = IO.read(dir / ".scalafmt.conf")
    val expected =
      """version = "3.8.0"
        |runner.dialect = scala213""".stripMargin

    assertNoDiff(content, expected)
  }

  withScalafmtConf {
    """version = 3.7.0
      |runner.dialect = scala213""".stripMargin
  }.test("updateVersion updates unquoted version") { dir =>
    implicit val versionFinder: VersionFinder = mockVersionFinder("3.8.0")

    val updated = Scalafmt.updateVersion(dir)

    assert(updated, "Should return true when version was updated")

    val content = IO.read(dir / ".scalafmt.conf")
    val expected =
      """version = 3.8.0
        |runner.dialect = scala213""".stripMargin

    assertNoDiff(content, expected)
  }

  withScalafmtConf {
    """version = "3.8.0"
      |runner.dialect = scala213""".stripMargin
  }.test("updateVersion returns false when already at latest") { dir =>
    implicit val versionFinder: VersionFinder = mockVersionFinder("3.8.0")

    val updated = Scalafmt.updateVersion(dir)

    assert(!updated, "Should return false when already at latest version")

    val content = IO.read(dir / ".scalafmt.conf")
    val expected =
      """version = "3.8.0"
        |runner.dialect = scala213""".stripMargin

    assertNoDiff(content, expected)
  }

  withoutScalafmtConf.test("updateVersion returns false when file doesn't exist") { dir =>
    implicit val versionFinder: VersionFinder = mockVersionFinder("3.8.0")

    val updated = Scalafmt.updateVersion(dir)

    assert(!updated, "Should return false when .scalafmt.conf doesn't exist")
  }

  withScalafmtConf {
    """runner.dialect = scala213
      |maxColumn = 120""".stripMargin
  }.test("updateVersion returns false when no version field exists") { dir =>
    implicit val versionFinder: VersionFinder = mockVersionFinder("3.8.0")

    val updated = Scalafmt.updateVersion(dir)

    assert(!updated, "Should return false when no version field exists")
  }

  withScalafmtConf {
    """  version = "3.7.0"
      |runner.dialect = scala213""".stripMargin
  }.test("updateVersion preserves leading whitespace") { dir =>
    implicit val versionFinder: VersionFinder = mockVersionFinder("3.8.0")

    val updated = Scalafmt.updateVersion(dir)

    assert(updated)

    val content = IO.read(dir / ".scalafmt.conf")
    val expected =
      """  version = "3.8.0"
        |runner.dialect = scala213""".stripMargin

    assertNoDiff(content, expected)
  }

  withScalafmtConf {
    """version="3.7.0"
      |runner.dialect = scala213""".stripMargin
  }.test("updateVersion preserves no spaces around equals") { dir =>
    implicit val versionFinder: VersionFinder = mockVersionFinder("3.8.0")

    val updated = Scalafmt.updateVersion(dir)

    assert(updated)

    val content = IO.read(dir / ".scalafmt.conf")
    val expected =
      """version="3.8.0"
        |runner.dialect = scala213""".stripMargin

    assertNoDiff(content, expected)
  }

  withScalafmtConf {
    """runner.dialect = scala213
      |version = "3.7.0"
      |maxColumn = 120""".stripMargin
  }.test("updateVersion updates version when not on first line") { dir =>
    implicit val versionFinder: VersionFinder = mockVersionFinder("3.8.0")

    val updated = Scalafmt.updateVersion(dir)

    assert(updated)

    val content = IO.read(dir / ".scalafmt.conf")
    val expected =
      """runner.dialect = scala213
        |version = "3.8.0"
        |maxColumn = 120""".stripMargin

    assertNoDiff(content, expected)
  }

  withScalafmtConf {
    """version = "3.7.0-RC1"
      |runner.dialect = scala213""".stripMargin
  }.test("updateVersion handles pre-release versions") { dir =>
    implicit val versionFinder: VersionFinder = mockVersionFinder("3.7.0-RC2")

    val updated = Scalafmt.updateVersion(dir)

    assert(updated)

    val content = IO.read(dir / ".scalafmt.conf")
    val expected =
      """version = "3.7.0-RC2"
        |runner.dialect = scala213""".stripMargin

    assertNoDiff(content, expected)
  }

  withScalafmtConf {
    """version = "3.7.0"
      |runner.dialect = scala213
      |maxColumn = 120
      |align.preset = more
      |rewrite.rules = [SortImports]""".stripMargin
  }.test("updateVersion preserves complex config") { dir =>
    implicit val versionFinder: VersionFinder = mockVersionFinder("3.8.0")

    val updated = Scalafmt.updateVersion(dir)

    assert(updated)

    val content = IO.read(dir / ".scalafmt.conf")
    val expected =
      """version = "3.8.0"
        |runner.dialect = scala213
        |maxColumn = 120
        |align.preset = more
        |rewrite.rules = [SortImports]""".stripMargin

    assertNoDiff(content, expected)
  }

  //////////////
  // Fixtures //
  //////////////

  // Mock VersionFinder that returns a specific "latest" version
  def mockVersionFinder(latestVersion: String): VersionFinder =
    (_, _, _, _) => List(Version.Numeric.from(latestVersion, Version.Numeric.Marker.NoMarker).get)

  // Fixture that creates a temp directory with a .scalafmt.conf file
  def withScalafmtConf(content: String): FunFixture[File] = FunFixture[File](
    setup = { _ =>
      val dir  = Files.createTempDirectory("scalafmt-test").toFile
      val file = dir / ".scalafmt.conf"
      IO.write(file, content)
      dir
    },
    teardown = { dir =>
      val file = dir / ".scalafmt.conf"
      Files.deleteIfExists(file.toPath)
      Files.deleteIfExists(dir.toPath)
      ()
    }
  )

  // Fixture for directory without .scalafmt.conf
  def withoutScalafmtConf: FunFixture[File] = FunFixture[File](
    setup = { _ =>
      Files.createTempDirectory("scalafmt-test-empty").toFile
    },
    teardown = { dir =>
      Files.deleteIfExists(dir.toPath)
      ()
    }
  )

}
