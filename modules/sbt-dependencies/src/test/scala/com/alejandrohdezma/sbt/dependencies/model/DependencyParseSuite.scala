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

package com.alejandrohdezma.sbt.dependencies.model

import sbt._
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.TestLogger
import com.alejandrohdezma.sbt.dependencies.finders.VersionFinder
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version

class DependencyParseSuite extends munit.FunSuite {

  implicit val logger: Logger = TestLogger()

  // Dummy VersionFinder that always returns 0.1.0
  implicit val dummyVersionFinder: VersionFinder = (_, _, _, _) =>
    List(Version.Numeric(List(0, 1, 0), None, Version.Numeric.Marker.NoMarker))

  test("parse cross-version dependency with version") {
    val result = Dependency.parse("org.typelevel::cats-core:2.10.0")

    val expected = Dependency(
      organization = "org.typelevel",
      name = "cats-core",
      version = Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
      crossVersion = CrossVersion.binary
    )

    assertEquals(result, expected)
  }

  test("parse cross-version dependency without version fails") {
    interceptMessage[Exception](
      "org.typelevel::cats-core is missing a version"
    ) {
      Dependency.parse("org.typelevel::cats-core")
    }
  }

  test("parseIncludingMissingVersion resolves cross-version dependency without version") {
    val result = Dependency.parseIncludingMissingVersion("org.typelevel::cats-core")

    val expected = Dependency(
      organization = "org.typelevel",
      name = "cats-core",
      version = Version.Numeric(List(0, 1, 0), None, Version.Numeric.Marker.NoMarker),
      crossVersion = CrossVersion.binary
    )

    assertEquals(result, expected)
  }

  test("parseIncludingMissingVersion carries compiler-plugin config when version is missing") {
    val result = Dependency.parseIncludingMissingVersion("org.typelevel::kind-projector:compiler-plugin")

    // Compiler-plugin + cross goes through the dual-shape heuristic. The dummy VersionFinder returns the same versions
    // for both queries, so the tie-breaker (full wins on tie) sets `crossVersion = CrossVersion.full`.
    val expected = Dependency(
      organization = "org.typelevel",
      name = "kind-projector",
      version = Version.Numeric(List(0, 1, 0), None, Version.Numeric.Marker.NoMarker),
      configuration = "compiler-plugin",
      crossVersion = sbt.librarymanagement.CrossVersion.full
    )

    assertEquals(result, expected)
  }

  test("parseIncludingMissingVersion carries sbt-plugin config when version is missing") {
    val result = Dependency.parseIncludingMissingVersion("ch.epfl.scala:sbt-scalafix:sbt-plugin")

    val expected = Dependency(
      organization = "ch.epfl.scala",
      name = "sbt-scalafix",
      version = Version.Numeric(List(0, 1, 0), None, Version.Numeric.Marker.NoMarker),
      crossVersion = CrossVersion.disabled,
      configuration = "sbt-plugin"
    )

    assertEquals(result, expected)
  }

  test("parse java dependency with version") {
    val result = Dependency.parse("com.google.guava:guava:32.1.0-jre")

    val expected = Dependency(
      organization = "com.google.guava",
      name = "guava",
      version = Version.Numeric(List(32, 1, 0), Some("-jre"), Version.Numeric.Marker.NoMarker),
      crossVersion = CrossVersion.disabled
    )

    assertEquals(result, expected)
  }

  test("parse java dependency without version fails") {
    interceptMessage[Exception](
      "com.google.guava:guava is missing a version"
    ) {
      Dependency.parse("com.google.guava:guava")
    }
  }

  test("parseIncludingMissingVersion resolves java dependency without version") {
    val result = Dependency.parseIncludingMissingVersion("com.google.guava:guava")

    val expected = Dependency(
      organization = "com.google.guava",
      name = "guava",
      version = Version.Numeric(List(0, 1, 0), None, Version.Numeric.Marker.NoMarker),
      crossVersion = CrossVersion.disabled
    )

    assertEquals(result, expected)
  }

  test("parse invalid dependency throws exception") {
    intercept[Exception] {
      Dependency.parse("invalid")
    }
  }

  // --- Edge cases with configuration ---

  test("parse cross-version dependency with version and configuration") {
    val result = Dependency.parse("org.scalameta::munit:1.2.1:test")

    val expected = Dependency(
      organization = "org.scalameta",
      name = "munit",
      version = Version.Numeric(List(1, 2, 1), None, Version.Numeric.Marker.NoMarker),
      crossVersion = CrossVersion.binary,
      configuration = "test"
    )

    assertEquals(result, expected)
  }

  test("parse java dependency with version and configuration") {
    val result = Dependency.parse("ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin")

    val expected = Dependency(
      organization = "ch.epfl.scala",
      name = "sbt-scalafix",
      version = Version.Numeric(List(0, 14, 5), None, Version.Numeric.Marker.NoMarker),
      crossVersion = CrossVersion.disabled,
      configuration = "sbt-plugin"
    )

    assertEquals(result, expected)
  }

  test("parse dependency with provided configuration") {
    val result = Dependency.parse("javax.servlet:javax.servlet-api:4.0.1:provided")

    val expected = Dependency(
      organization = "javax.servlet",
      name = "javax.servlet-api",
      version = Version.Numeric(List(4, 0, 1), None, Version.Numeric.Marker.NoMarker),
      crossVersion = CrossVersion.disabled,
      configuration = "provided"
    )

    assertEquals(result, expected)
  }

  // --- Edge cases with version markers ---

  test("parse dependency with exact version marker") {
    val result = Dependency.parse("org.typelevel::cats-core:=2.10.0")

    assertEquals(result.version.asInstanceOf[Version.Numeric].marker, Version.Numeric.Marker.Exact)
    assertEquals(result.version.toVersionString, "2.10.0")
  }

  test("parse dependency with major version marker") {
    val result = Dependency.parse("org.typelevel::cats-core:^2.10.0")

    assertEquals(result.version.asInstanceOf[Version.Numeric].marker, Version.Numeric.Marker.Major)
    assertEquals(result.version.toVersionString, "2.10.0")
  }

  test("parse dependency with minor version marker") {
    val result = Dependency.parse("org.typelevel::cats-core:~2.10.0")

    assertEquals(result.version.asInstanceOf[Version.Numeric].marker, Version.Numeric.Marker.Minor)
    assertEquals(result.version.toVersionString, "2.10.0")
  }

  test("parse dependency with version marker and configuration") {
    val result = Dependency.parse("org.scalameta::munit:=1.2.1:test")

    assertEquals(result.version.asInstanceOf[Version.Numeric].marker, Version.Numeric.Marker.Exact)
    assertEquals(result.version.toVersionString, "1.2.1")
    assertEquals(result.configuration, "test")
  }

  // --- Edge cases with whitespace ---

  test("parse dependency trims organization whitespace") {
    val result = Dependency.parse("  org.typelevel  ::cats-core:2.10.0")

    assertEquals(result.organization, "org.typelevel")
  }

  test("parse dependency trims name whitespace") {
    val result = Dependency.parse("org.typelevel::  cats-core  :2.10.0")

    assertEquals(result.name, "cats-core")
  }

  // --- Edge cases with version suffixes ---

  test("parse dependency with .Final suffix") {
    val result = Dependency.parse("org.hibernate:hibernate-core:5.6.15.Final")

    assertEquals(result.version.asInstanceOf[Version.Numeric].parts, List(5, 6, 15))
    assertEquals(result.version.asInstanceOf[Version.Numeric].suffix, Some(".Final"))
  }

  test("parse dependency with -M suffix (milestone)") {
    val result = Dependency.parse("org.scala-lang:scala3-library_3:3.4.0-RC1")

    assertEquals(result.version.asInstanceOf[Version.Numeric].parts, List(3, 4, 0))
    assertEquals(result.version.asInstanceOf[Version.Numeric].suffix, Some("-RC1"))
  }

  test("parse dependency with 4-part version") {
    val result = Dependency.parse("io.netty:netty-all:4.1.100.Final")

    assertEquals(result.version.asInstanceOf[Version.Numeric].parts, List(4, 1, 100))
    assertEquals(result.version.asInstanceOf[Version.Numeric].suffix, Some(".Final"))
  }

  test("parse dependency with 2-part version") {
    val result = Dependency.parse("org.scala-lang:scala-library:2.13")

    assertEquals(result.version.asInstanceOf[Version.Numeric].parts, List(2, 13))
    assertEquals(result.version.asInstanceOf[Version.Numeric].suffix, None)
  }

  // --- Variable version tests ---

  test("parse dependency with variable version produces unresolved Variable") {
    val result = Dependency.parse("org.typelevel::cats-core:{{catsVersion}}")

    result.version match {
      case v: Version.Variable =>
        assertEquals(v.name, "catsVersion")
        assertEquals(v.resolved, None)
        assertEquals(v.show, "{{catsVersion}}")
      case _ =>
        fail("Expected Variable version")
    }
  }

  test("parse dependency with variable version and configuration") {
    val result = Dependency.parse("org.scalameta::munit:{{munitVersion}}:test")

    result.version match {
      case v: Version.Variable =>
        assertEquals(v.name, "munitVersion")
        assertEquals(v.resolved, None)
      case _ =>
        fail("Expected Variable version")
    }
    assertEquals(result.configuration, "test")
  }

  test("parse dependency with undefined variable produces unresolved Variable") {
    val result = Dependency.parse("org.typelevel::cats-core:{{unknownVar}}")

    result.version match {
      case v: Version.Variable =>
        assertEquals(v.name, "unknownVar")
        assertEquals(v.resolved, None)
      case _ => fail("Expected Variable version")
    }
  }

  test("parse variable dependency toLine preserves variable syntax") {
    val result = Dependency.parse("org.typelevel::cats-core:{{catsVersion}}")

    assertEquals(result.toLine, "org.typelevel::cats-core:{{catsVersion}}")
  }

  test("parse java dependency with variable version produces Java-cross unresolved Variable") {
    val result = Dependency.parse("com.fasterxml.jackson.core:jackson-core:{{jacksonVersion}}")

    result.version match {
      case v: Version.Variable =>
        assertEquals(v.name, "jacksonVersion")
        assertEquals(v.resolved, None)
      case _ =>
        fail("Expected Variable version")
    }
    assertEquals(result.isCross, false)
  }

  // --- resolveVariable tests ---

  test("resolveVariable populates resolved when the variable is in the resolver map") {
    val resolvers: Map[String, OrganizationArtifactName => ModuleID] = Map(
      "catsVersion" -> { _ % "2.10.0" }
    )

    val result = Dependency.parse("org.typelevel::cats-core:{{catsVersion}}").resolveVariable(resolvers)

    result.version match {
      case v: Version.Variable =>
        assertEquals(v.name, "catsVersion")
        assertEquals(v.resolved.map(_.parts), Some(List(2, 10, 0)))
        assertEquals(v.toVersionString, "2.10.0")
      case _ =>
        fail("Expected Variable version")
    }
  }

  test("resolveVariable leaves the dep unchanged when the variable is missing from the resolver map") {
    val resolvers: Map[String, OrganizationArtifactName => ModuleID] = Map(
      "otherVar" -> { _ % "1.0.0" }
    )

    val result = Dependency.parse("org.typelevel::cats-core:{{unknownVar}}").resolveVariable(resolvers)

    result.version match {
      case v: Version.Variable =>
        assertEquals(v.name, "unknownVar")
        assertEquals(v.resolved, None)
      case _ => fail("Expected Variable version")
    }
  }

  test("resolveVariable is a no-op for already-resolved variables and for numeric versions") {
    val resolvers: Map[String, OrganizationArtifactName => ModuleID] = Map(
      "v" -> { _ % "9.9.9" }
    )

    val numeric = Dependency.parse("org.typelevel::cats-core:2.10.0")
    assertEquals(numeric.resolveVariable(resolvers), numeric)

    val alreadyResolved = Dependency.parse("org.typelevel::cats-core:{{v}}").resolveVariable(resolvers)
    assertEquals(alreadyResolved.resolveVariable(resolvers), alreadyResolved)
  }

}
