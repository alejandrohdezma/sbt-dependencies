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

package com.alejandrohdezma.sbt.dependencies.io

import com.typesafe.config.ConfigFactory

class GroupConfigSuite extends munit.FunSuite {

  def parseGroup(hocon: String, group: String): Either[String, GroupConfig] = {
    val config = ConfigFactory.parseString(hocon)
    GroupConfig.parse(config, group)
  }

  // --- parse() tests: Simple format ---

  test("parse simple format returns Simple with dependencies") {
    val result = parseGroup("""my-group = ["dep1", "dep2", "dep3"]""", "my-group")

    assertEquals(result, Right(GroupConfig.Simple(List("dep1", "dep2", "dep3"))))
  }

  test("parse empty list returns Simple with empty dependencies") {
    val result = parseGroup("my-group = []", "my-group")

    assertEquals(result, Right(GroupConfig.Simple(Nil)))
  }

  // --- parse() tests: Advanced format ---

  test("parse advanced format with dependencies only") {
    val result = parseGroup(
      """|my-group {
         |  dependencies = ["dep1", "dep2"]
         |}""".stripMargin,
      "my-group"
    )

    assertEquals(result, Right(GroupConfig.Advanced(List("dep1", "dep2"), Nil)))
  }

  test("parse advanced format with scalaVersions only") {
    val result = parseGroup(
      """|my-group {
         |  scala-versions = ["2.13.12", "2.12.18"]
         |}""".stripMargin,
      "my-group"
    )

    assertEquals(result, Right(GroupConfig.Advanced(Nil, List("2.13.12", "2.12.18"))))
  }

  test("parse advanced format with both dependencies and scalaVersions") {
    val result = parseGroup(
      """|my-group {
         |  scala-versions = ["2.13.12", "3.3.1"]
         |  dependencies = [
         |    "org.typelevel::cats-core:2.10.0"
         |    "org.scalameta::munit:1.2.1:test"
         |  ]
         |}""".stripMargin,
      "my-group"
    )

    assertEquals(
      result,
      Right(
        GroupConfig.Advanced(
          List("org.typelevel::cats-core:2.10.0", "org.scalameta::munit:1.2.1:test"),
          List("2.13.12", "3.3.1")
        )
      )
    )
  }

  test("parse advanced format with empty dependencies list") {
    val result = parseGroup("my-group { dependencies = [] }", "my-group")

    assertEquals(result, Right(GroupConfig.Advanced(Nil, Nil)))
  }

  test("parse advanced format ignores unknown fields") {
    val result = parseGroup(
      """|my-group {
         |  unknownField = "someValue"
         |  dependencies = ["dep1"]
         |  anotherField = 123
         |}""".stripMargin,
      "my-group"
    )

    assertEquals(result, Right(GroupConfig.Advanced(List("dep1"), Nil)))
  }

  // --- parse() tests: scala-version (singular) alias ---

  test("parse advanced format with scala-version (singular)") {
    val result = parseGroup("""my-group { scala-version = "2.13.12" }""", "my-group")

    assertEquals(result, Right(GroupConfig.Advanced(Nil, List("2.13.12"))))
  }

  test("parse advanced format with scala-version and dependencies") {
    val result = parseGroup(
      """|my-group {
         |  scala-version = "3.3.1"
         |  dependencies = ["org.typelevel::cats-core:2.10.0"]
         |}""".stripMargin,
      "my-group"
    )

    assertEquals(result, Right(GroupConfig.Advanced(List("org.typelevel::cats-core:2.10.0"), List("3.3.1"))))
  }

  test("parse returns error when both scala-versions and scala-version are present") {
    val result = parseGroup(
      """|my-group {
         |  scala-versions = ["2.13.12", "2.12.18"]
         |  scala-version = "3.3.1"
         |}""".stripMargin,
      "my-group"
    )

    assert(result.isLeft)
    assert(result.left.exists(_.contains("Only one")))
  }

  test("parse returns error for invalid scala-version type") {
    val result = parseGroup("""my-group { scala-version = ["2.13.12"] }""", "my-group")

    assert(result.isLeft)
    assert(result.left.exists(_.contains("must be a string")))
  }

  // --- parse() tests: Error cases ---

  test("parse returns error for empty scalaVersions list") {
    val result = parseGroup("my-group { scala-versions = [] }", "my-group")

    assert(result.isLeft)
    assert(result.left.exists(_.contains("cannot be empty")))
  }

  // --- format() tests: Simple format ---

  test("format Simple produces correct HOCON") {
    val config = GroupConfig.Simple(List("dep1", "dep2", "dep3"))
    val result = config.format("my-project")

    val expected =
      """|my-project = [
         |  "dep1"
         |  "dep2"
         |  "dep3"
         |]""".stripMargin

    assertEquals(result, expected)
  }

  test("format Simple with single dependency") {
    val config = GroupConfig.Simple(List("org.typelevel::cats-core:2.10.0"))
    val result = config.format("sbt-build")

    val expected =
      """|sbt-build = [
         |  "org.typelevel::cats-core:2.10.0"
         |]""".stripMargin

    assertEquals(result, expected)
  }

  // --- format() tests: Advanced format ---

  test("format Advanced with dependencies only") {
    val config = GroupConfig.Advanced(List("dep1", "dep2"), Nil)
    val result = config.format("my-project")

    val expected =
      """|my-project {
         |  dependencies = [
         |    "dep1"
         |    "dep2"
         |  ]
         |}""".stripMargin

    assertEquals(result, expected)
  }

  test("format Advanced with scalaVersions only") {
    val config = GroupConfig.Advanced(Nil, List("2.13.12", "2.12.18"))
    val result = config.format("my-project")

    val expected =
      """|my-project {
         |  scala-versions = ["2.13.12", "2.12.18"]
         |  dependencies = []
         |}""".stripMargin

    assertEquals(result, expected)
  }

  test("format Advanced with both dependencies and scalaVersions") {
    val config = GroupConfig.Advanced(List("dep1", "dep2"), List("2.13.12", "3.3.1"))
    val result = config.format("my-project")

    val expected =
      """|my-project {
         |  scala-versions = ["2.13.12", "3.3.1"]
         |  dependencies = [
         |    "dep1"
         |    "dep2"
         |  ]
         |}""".stripMargin

    assertEquals(result, expected)
  }

  test("format Advanced with empty dependencies shows empty array") {
    val config = GroupConfig.Advanced(Nil, Nil)
    val result = config.format("empty-project")

    val expected =
      """|empty-project {
         |  dependencies = []
         |}""".stripMargin

    assertEquals(result, expected)
  }

  test("format Advanced with single scalaVersion uses singular key") {
    val config = GroupConfig.Advanced(List("dep1"), List("2.13.12"))
    val result = config.format("my-project")

    val expected =
      """|my-project {
         |  scala-version = "2.13.12"
         |  dependencies = [
         |    "dep1"
         |  ]
         |}""".stripMargin

    assertEquals(result, expected)
  }

  // --- scalaVersions property tests ---

  test("Simple.scalaVersions returns empty list") {
    val config = GroupConfig.Simple(List("dep1"))

    assertEquals(config.scalaVersions, Nil)
  }

  test("Advanced.scalaVersions returns the configured versions") {
    val config = GroupConfig.Advanced(List("dep1"), List("2.13.12", "2.12.18"))

    assertEquals(config.scalaVersions, List("2.13.12", "2.12.18"))
  }

  test("Advanced.scalaVersions returns empty list when not configured") {
    val config = GroupConfig.Advanced(List("dep1"), Nil)

    assertEquals(config.scalaVersions, Nil)
  }

  // --- dependencies property tests ---

  test("Simple.dependencies returns the list") {
    val config = GroupConfig.Simple(List("dep1", "dep2"))

    assertEquals(config.dependencies, List("dep1", "dep2"))
  }

  test("Advanced.dependencies returns the list") {
    val config = GroupConfig.Advanced(List("dep1", "dep2"), List("2.13.12"))

    assertEquals(config.dependencies, List("dep1", "dep2"))
  }

}
