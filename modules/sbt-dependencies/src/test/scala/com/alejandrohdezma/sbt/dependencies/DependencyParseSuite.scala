/*
 * Copyright 2025 Alejandro Hernández <https://github.com/alejandrohdezma>
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

class DependencyParseSuite extends munit.FunSuite {

  implicit val logger: Logger = new Logger {

    override def trace(t: => Throwable): Unit = ()

    override def success(message: => String): Unit = ()

    override def log(level: Level.Value, message: => String): Unit = ()

  }

  // Dummy VersionFinder that always returns 0.1.0
  implicit val dummyVersionFinder: Utils.VersionFinder = (_, _, _, _) =>
    List(Version(List(0, 1, 0), None, Version.Marker.NoMarker))

  test("parse cross-version dependency with version") {
    val result = Dependency.parse("org.typelevel::cats-core:2.10.0", "test")

    val expected = Dependency(
      organization = "org.typelevel",
      name = "cats-core",
      version = Version(List(2, 10, 0), None, Version.Marker.NoMarker),
      isCross = true,
      group = "test"
    )

    assertEquals(result, expected)
  }

  test("parse cross-version dependency without version") {
    val result = Dependency.parse("org.typelevel::cats-core", "test")

    val expected = Dependency(
      organization = "org.typelevel",
      name = "cats-core",
      version = Version(List(0, 1, 0), None, Version.Marker.NoMarker),
      isCross = true,
      group = "test"
    )

    assertEquals(result, expected)
  }

  test("parse java dependency with version") {
    val result = Dependency.parse("com.google.guava:guava:32.1.0-jre", "test")

    val expected = Dependency(
      organization = "com.google.guava",
      name = "guava",
      version = Version(List(32, 1, 0), Some("-jre"), Version.Marker.NoMarker),
      isCross = false,
      group = "test"
    )

    assertEquals(result, expected)
  }

  test("parse java dependency without version") {
    val result = Dependency.parse("com.google.guava:guava", "test")

    val expected = Dependency(
      organization = "com.google.guava",
      name = "guava",
      version = Version(List(0, 1, 0), None, Version.Marker.NoMarker),
      isCross = false,
      group = "test"
    )

    assertEquals(result, expected)
  }

  test("parse invalid dependency throws exception") {
    intercept[Exception] {
      Dependency.parse("invalid", "test")
    }
  }

  // --- Edge cases with configuration ---

  test("parse cross-version dependency with version and configuration") {
    val result = Dependency.parse("org.scalameta::munit:1.2.1:test", "my-project")

    val expected = Dependency(
      organization = "org.scalameta",
      name = "munit",
      version = Version(List(1, 2, 1), None, Version.Marker.NoMarker),
      isCross = true,
      group = "my-project",
      configuration = "test"
    )

    assertEquals(result, expected)
  }

  test("parse java dependency with version and configuration") {
    val result = Dependency.parse("ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin", "sbt-build")

    val expected = Dependency(
      organization = "ch.epfl.scala",
      name = "sbt-scalafix",
      version = Version(List(0, 14, 5), None, Version.Marker.NoMarker),
      isCross = false,
      group = "sbt-build",
      configuration = "sbt-plugin"
    )

    assertEquals(result, expected)
  }

  test("parse dependency with provided configuration") {
    val result = Dependency.parse("javax.servlet:javax.servlet-api:4.0.1:provided", "web")

    val expected = Dependency(
      organization = "javax.servlet",
      name = "javax.servlet-api",
      version = Version(List(4, 0, 1), None, Version.Marker.NoMarker),
      isCross = false,
      group = "web",
      configuration = "provided"
    )

    assertEquals(result, expected)
  }

  // --- Edge cases with version markers ---

  test("parse dependency with exact version marker") {
    val result = Dependency.parse("org.typelevel::cats-core:=2.10.0", "test")

    assertEquals(result.version.marker, Version.Marker.Exact)
    assertEquals(result.version.toVersionString, "2.10.0")
  }

  test("parse dependency with major version marker") {
    val result = Dependency.parse("org.typelevel::cats-core:^2.10.0", "test")

    assertEquals(result.version.marker, Version.Marker.Major)
    assertEquals(result.version.toVersionString, "2.10.0")
  }

  test("parse dependency with minor version marker") {
    val result = Dependency.parse("org.typelevel::cats-core:~2.10.0", "test")

    assertEquals(result.version.marker, Version.Marker.Minor)
    assertEquals(result.version.toVersionString, "2.10.0")
  }

  test("parse dependency with version marker and configuration") {
    val result = Dependency.parse("org.scalameta::munit:=1.2.1:test", "my-project")

    assertEquals(result.version.marker, Version.Marker.Exact)
    assertEquals(result.version.toVersionString, "1.2.1")
    assertEquals(result.configuration, "test")
  }

  // --- Edge cases with whitespace ---

  test("parse dependency trims organization whitespace") {
    val result = Dependency.parse("  org.typelevel  ::cats-core:2.10.0", "test")

    assertEquals(result.organization, "org.typelevel")
  }

  test("parse dependency trims name whitespace") {
    val result = Dependency.parse("org.typelevel::  cats-core  :2.10.0", "test")

    assertEquals(result.name, "cats-core")
  }

  // --- Edge cases with version suffixes ---

  test("parse dependency with .Final suffix") {
    val result = Dependency.parse("org.hibernate:hibernate-core:5.6.15.Final", "test")

    assertEquals(result.version.parts, List(5, 6, 15))
    assertEquals(result.version.suffix, Some(".Final"))
  }

  test("parse dependency with -M suffix (milestone)") {
    val result = Dependency.parse("org.scala-lang:scala3-library_3:3.4.0-RC1", "test")

    assertEquals(result.version.parts, List(3, 4, 0))
    assertEquals(result.version.suffix, Some("-RC1"))
  }

  test("parse dependency with 4-part version") {
    val result = Dependency.parse("io.netty:netty-all:4.1.100.Final", "test")

    assertEquals(result.version.parts, List(4, 1, 100))
    assertEquals(result.version.suffix, Some(".Final"))
  }

  test("parse dependency with 2-part version") {
    val result = Dependency.parse("org.scala-lang:scala-library:2.13", "test")

    assertEquals(result.version.parts, List(2, 13))
    assertEquals(result.version.suffix, None)
  }

}
