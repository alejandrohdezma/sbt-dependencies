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

import sbt.util.Level
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Dependency.Version

class UtilsSuite extends munit.FunSuite {

  implicit val logger: Logger = new Logger {

    override def trace(t: => Throwable): Unit = ()

    override def success(message: => String): Unit = ()

    override def log(level: Level.Value, message: => String): Unit = ()

  }

  // Helper to create a mock VersionFinder that returns specific versions for specific modules
  def mockVersionFinder(versions: Map[(String, String), List[String]]): Utils.VersionFinder =
    (organization: String, name: String, _: Boolean, _: Boolean) =>
      versions
        .getOrElse((organization, name), Nil)
        .collect { case Version.Numeric(v) => v }

  // --- findLatestScalaVersion tests ---

  test("findLatestScalaVersion finds latest 2.13.x version") {
    implicit val versionFinder: Utils.VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala-library") -> List("2.13.10", "2.13.11", "2.13.12", "2.13.14", "2.12.18")
      )
    )

    val result = Utils.findLatestScalaVersion("2.13.12")

    assertEquals(result, "2.13.14")
  }

  test("findLatestScalaVersion finds latest 2.12.x version") {
    implicit val versionFinder: Utils.VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala-library") -> List("2.12.15", "2.12.16", "2.12.17", "2.12.18", "2.12.19", "2.13.12")
      )
    )

    val result = Utils.findLatestScalaVersion("2.12.17")

    assertEquals(result, "2.12.19")
  }

  test("findLatestScalaVersion finds latest 3.3.x version") {
    implicit val versionFinder: Utils.VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala3-library_3") -> List("3.3.0", "3.3.1", "3.3.3", "3.4.0", "3.5.0")
      )
    )

    val result = Utils.findLatestScalaVersion("3.3.1")

    assertEquals(result, "3.3.3")
  }

  test("findLatestScalaVersion finds latest 3.4.x version") {
    implicit val versionFinder: Utils.VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala3-library_3") -> List("3.3.3", "3.4.0", "3.4.1", "3.4.2", "3.5.0")
      )
    )

    val result = Utils.findLatestScalaVersion("3.4.0")

    assertEquals(result, "3.4.2")
  }

  test("findLatestScalaVersion returns same version when already latest") {
    implicit val versionFinder: Utils.VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala-library") -> List("2.13.10", "2.13.11", "2.13.12")
      )
    )

    val result = Utils.findLatestScalaVersion("2.13.12")

    assertEquals(result, "2.13.12")
  }

  test("findLatestScalaVersion returns original for unrecognized format") {
    implicit val versionFinder: Utils.VersionFinder = mockVersionFinder(Map.empty)

    val result = Utils.findLatestScalaVersion("invalid-version")

    assertEquals(result, "invalid-version")
  }

  test("findLatestScalaVersion uses scala-library for Scala 2") {
    // We verify this by providing versions only for scala-library
    // If it queried the wrong module, it would fail to find a version
    implicit val versionFinder: Utils.VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala-library") -> List("2.13.14")
      )
    )

    val result = Utils.findLatestScalaVersion("2.13.12")

    assertEquals(result, "2.13.14")
  }

  test("findLatestScalaVersion uses scala3-library_3 for Scala 3") {
    // We verify this by providing versions only for scala3-library_3
    // If it queried the wrong module, it would fail to find a version
    implicit val versionFinder: Utils.VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala3-library_3") -> List("3.3.3")
      )
    )

    val result = Utils.findLatestScalaVersion("3.3.1")

    assertEquals(result, "3.3.3")
  }

  test("findLatestScalaVersion handles version without patch") {
    implicit val versionFinder: Utils.VersionFinder = mockVersionFinder(Map.empty)

    // This should return original because "2.13" doesn't match the regex ^(\d+\.\d+)\..*
    val result = Utils.findLatestScalaVersion("2.13")

    assertEquals(result, "2.13")
  }

}
