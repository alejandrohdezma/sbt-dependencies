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

import com.alejandrohdezma.sbt.dependencies.model.Group
import com.typesafe.config.ConfigFactory

class GroupConfigSuite extends munit.FunSuite {

  /** Helper to create an AnnotatedDependency */
  implicit def dep(line: String): AnnotatedDependency = AnnotatedDependency(line, None) // scalafix:ok

  /** Helper to create an AnnotatedDependency with a note. */
  private def dep(line: String, note: String): AnnotatedDependency = AnnotatedDependency(line, Some(note))

  def parseGroup(hocon: String, group: String): Either[String, GroupConfig] = {
    val config = ConfigFactory.parseString(hocon)
    GroupConfig.parse(config, Group(group))
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

  // --- parse() tests: java-version ---

  test("parse advanced format with java-version") {
    val result = parseGroup(
      """|my-group {
         |  java-version = "25"
         |  dependencies = ["dep1"]
         |}""".stripMargin,
      "my-group"
    )

    assertEquals(result, Right(GroupConfig.Advanced(List("dep1"), Nil, Some("25"))))
  }

  test("parse advanced format with both java-version and scala-versions") {
    val result = parseGroup(
      """|my-group {
         |  java-version = "25"
         |  scala-versions = ["2.13.12", "3.3.1"]
         |  dependencies = ["dep1"]
         |}""".stripMargin,
      "my-group"
    )

    assertEquals(result, Right(GroupConfig.Advanced(List("dep1"), List("2.13.12", "3.3.1"), Some("25"))))
  }

  test("parse advanced format without java-version returns None") {
    val result = parseGroup("""my-group { dependencies = ["dep1"] }""", "my-group")

    assertEquals(result, Right(GroupConfig.Advanced(List("dep1"), Nil, None)))
  }

  test("parse returns error when java-version is not a string") {
    val result = parseGroup("""my-group { java-version = 25 }""", "my-group")

    assert(result.isLeft)
    assert(result.left.exists(_.contains("must be a string")))
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

  // --- parse() tests: Object format with notes ---

  test("parse simple format with object entry containing note") {
    val result = parseGroup(
      """|my-group = [
         |  { dependency = "org::name:^1.0.0", note = "Pinned to major 1" }
         |  "org2::name2:2.0.0"
         |]""".stripMargin,
      "my-group"
    )

    assertEquals(
      result,
      Right(GroupConfig.Simple(List(dep("org::name:^1.0.0", "Pinned to major 1"), "org2::name2:2.0.0")))
    )
  }

  test("parse advanced format with object entry containing note") {
    val result = parseGroup(
      """|my-group {
         |  dependencies = [
         |    { dependency = "org::name:=1.0.0", note = "Exact pin for compat" }
         |    "org2::name2:2.0.0"
         |  ]
         |}""".stripMargin,
      "my-group"
    )

    assertEquals(
      result,
      Right(GroupConfig.Advanced(List(dep("org::name:=1.0.0", "Exact pin for compat"), "org2::name2:2.0.0"), Nil))
    )
  }

  test("parse returns error for object entry without dependency field") {
    val result = parseGroup("""my-group = [{ note = "missing dep" }]""", "my-group")

    assert(result.isLeft)
    assert(result.left.exists(_.contains("'dependency'")))
  }

  test("parse returns error for object entry without note or intransitive field") {
    val result = parseGroup("""my-group = [{ dependency = "org::name:1.0" }]""", "my-group")

    assert(result.isLeft)
    assert(result.left.exists(_.contains("'note', 'intransitive', or 'scala-filter'")))
  }

  // --- parse() tests: Object format with intransitive ---

  test("parse simple format with object entry containing intransitive flag") {
    val result = parseGroup(
      """my-group = [{ dependency = "org::name:=1.0.0", intransitive = true }]""",
      "my-group"
    )

    assertEquals(
      result,
      Right(GroupConfig.Simple(List(AnnotatedDependency("org::name:=1.0.0", None, intransitive = true))))
    )
  }

  test("parse simple format with object entry containing both note and intransitive") {
    val result = parseGroup(
      """my-group = [{ dependency = "org::name:=1.0.0", note = "reason", intransitive = true }]""",
      "my-group"
    )

    assertEquals(
      result,
      Right(GroupConfig.Simple(List(AnnotatedDependency("org::name:=1.0.0", Some("reason"), intransitive = true))))
    )
  }

  // --- format() tests: Simple format ---

  test("format Simple produces correct HOCON") {
    val config = GroupConfig.Simple(List("dep1", "dep2", "dep3"))
    val result = config.format(Group("my-project"))

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
    val result = config.format(Group.`sbt-build`)

    val expected =
      """|sbt-build = [
         |  "org.typelevel::cats-core:2.10.0"
         |]""".stripMargin

    assertEquals(result, expected)
  }

  // --- format() tests: Advanced format ---

  test("format Advanced with dependencies only") {
    val config = GroupConfig.Advanced(List("dep1", "dep2"), Nil)
    val result = config.format(Group("my-project"))

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
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project {
         |  scala-versions = ["2.13.12", "2.12.18"]
         |  dependencies = []
         |}""".stripMargin

    assertEquals(result, expected)
  }

  test("format Advanced with both dependencies and scalaVersions") {
    val config = GroupConfig.Advanced(List("dep1", "dep2"), List("2.13.12", "3.3.1"))
    val result = config.format(Group("my-project"))

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
    val result = config.format(Group("empty-project"))

    val expected =
      """|empty-project {
         |  dependencies = []
         |}""".stripMargin

    assertEquals(result, expected)
  }

  test("format Advanced with single scalaVersion uses singular key") {
    val config = GroupConfig.Advanced(List("dep1"), List("2.13.12"))
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project {
         |  scala-version = "2.13.12"
         |  dependencies = [
         |    "dep1"
         |  ]
         |}""".stripMargin

    assertEquals(result, expected)
  }

  // --- format() tests: java-version ---

  test("format Advanced with java-version only") {
    val config = GroupConfig.Advanced(List("dep1"), Nil, Some("25"))
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project {
         |  java-version = "25"
         |  dependencies = [
         |    "dep1"
         |  ]
         |}""".stripMargin

    assertEquals(result, expected)
  }

  test("format Advanced with both java-version and scala-versions") {
    val config = GroupConfig.Advanced(List("dep1"), List("2.13.12", "3.3.1"), Some("25"))
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project {
         |  java-version = "25"
         |  scala-versions = ["2.13.12", "3.3.1"]
         |  dependencies = [
         |    "dep1"
         |  ]
         |}""".stripMargin

    assertEquals(result, expected)
  }

  test("format Advanced with java-version and no dependencies") {
    val config = GroupConfig.Advanced(Nil, Nil, Some("25"))
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project {
         |  java-version = "25"
         |  dependencies = []
         |}""".stripMargin

    assertEquals(result, expected)
  }

  // --- format() tests: Object format with notes ---

  test("format Simple with short note uses single-line object") {
    val config = GroupConfig.Simple(List(dep("org::name:^1.0.0", "v2 drops Scala 2.12")))
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project = [
         |  { dependency = "org::name:^1.0.0", note = "v2 drops Scala 2.12" }
         |]""".stripMargin

    assertEquals(result, expected)
  }

  test("format Simple with long note uses multi-line object") {
    val longNote =
      "This dependency is pinned because the next major version drops support for Scala 2.12 and we still need cross-building"
    val config = GroupConfig.Simple(List(dep("org.typelevel::cats-core:^2.10.0", longNote)))
    val result = config.format(Group("my-project"))

    val expected =
      s"""|my-project = [
          |  {
          |    dependency = "org.typelevel::cats-core:^2.10.0"
          |    note = "$longNote"
          |  }
          |]""".stripMargin

    assertEquals(result, expected)
  }

  test("format mixed string and object entries") {
    val config = GroupConfig.Simple(
      List(
        dep("org::name:^1.0.0", "Pinned to major 1"),
        "org2::name2:2.0.0"
      )
    )
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project = [
         |  { dependency = "org::name:^1.0.0", note = "Pinned to major 1" }
         |  "org2::name2:2.0.0"
         |]""".stripMargin

    assertEquals(result, expected)
  }

  test("format Advanced with note in dependencies") {
    val config = GroupConfig.Advanced(
      List(dep("org::name:=1.0.0", "Exact pin for compat"), "org2::name2:2.0.0"),
      List("2.13.12")
    )
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project {
         |  scala-version = "2.13.12"
         |  dependencies = [
         |    { dependency = "org::name:=1.0.0", note = "Exact pin for compat" }
         |    "org2::name2:2.0.0"
         |  ]
         |}""".stripMargin

    assertEquals(result, expected)
  }

  // --- format() tests: Object format with intransitive ---

  test("format Simple with intransitive only uses single-line object") {
    val config = GroupConfig.Simple(List(AnnotatedDependency("org::name:=1.0.0", None, intransitive = true)))
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project = [
         |  { dependency = "org::name:=1.0.0", intransitive = true }
         |]""".stripMargin

    assertEquals(result, expected)
  }

  test("format Simple with note and intransitive uses single-line object") {
    val config =
      GroupConfig.Simple(List(AnnotatedDependency("org::name:=1.0.0", Some("reason"), intransitive = true)))
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project = [
         |  { dependency = "org::name:=1.0.0", note = "reason", intransitive = true }
         |]""".stripMargin

    assertEquals(result, expected)
  }

  test("format Simple with long note and intransitive uses multi-line object") {
    val longNote =
      "This dependency is pinned because the next major version drops support for Scala 2.12 and we still need cross-building"
    val config =
      GroupConfig.Simple(List(AnnotatedDependency("org.typelevel::cats-core:^2.10.0", Some(longNote), true)))
    val result = config.format(Group("my-project"))

    val expected =
      s"""|my-project = [
          |  {
          |    dependency = "org.typelevel::cats-core:^2.10.0"
          |    note = "$longNote"
          |    intransitive = true
          |  }
          |]""".stripMargin

    assertEquals(result, expected)
  }

  // --- parse() tests: Object format with scala-filter ---

  test("parse simple format with object entry containing scala-filter") {
    val result = parseGroup(
      """my-group = [{ dependency = "org:name:1.0", scala-filter = "2" }]""",
      "my-group"
    )

    assertEquals(
      result,
      Right(GroupConfig.Simple(List(AnnotatedDependency("org:name:1.0", scalaFilter = Some("2")))))
    )
  }

  test("parse advanced format with object entry containing scala-filter") {
    val result = parseGroup(
      """|my-group {
         |  scala-versions = ["2.13.16", "3.3.7"]
         |  dependencies = [
         |    { dependency = "org:name:1.0", scala-filter = "2" }
         |    "org2::name2:2.0.0"
         |  ]
         |}""".stripMargin,
      "my-group"
    )

    assertEquals(
      result,
      Right(
        GroupConfig.Advanced(
          List(AnnotatedDependency("org:name:1.0", scalaFilter = Some("2")), "org2::name2:2.0.0"),
          List("2.13.16", "3.3.7")
        )
      )
    )
  }

  test("parse object entry with scala-filter and note") {
    val result = parseGroup(
      """my-group = [{ dependency = "org:name:1.0", note = "Scala 2 only", scala-filter = "2.13" }]""",
      "my-group"
    )

    assertEquals(
      result,
      Right(
        GroupConfig.Simple(
          List(AnnotatedDependency("org:name:1.0", note = Some("Scala 2 only"), scalaFilter = Some("2.13")))
        )
      )
    )
  }

  test("parse object entry with all annotation fields") {
    val result = parseGroup(
      """my-group = [{ dependency = "org:name:1.0", note = "reason", intransitive = true, scala-filter = "3" }]""",
      "my-group"
    )

    assertEquals(
      result,
      Right(
        GroupConfig.Simple(
          List(
            AnnotatedDependency("org:name:1.0", note = Some("reason"), intransitive = true, scalaFilter = Some("3"))
          )
        )
      )
    )
  }

  // --- format() tests: Object format with scala-filter ---

  test("format Simple with scala-filter only uses single-line object") {
    val config = GroupConfig.Simple(List(AnnotatedDependency("org:name:1.0", scalaFilter = Some("2"))))
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project = [
         |  { dependency = "org:name:1.0", scala-filter = "2" }
         |]""".stripMargin

    assertEquals(result, expected)
  }

  test("format Advanced with scala-filter in dependencies") {
    val config = GroupConfig.Advanced(
      List(AnnotatedDependency("org:name:1.0", scalaFilter = Some("2")), "org2::name2:2.0.0"),
      List("2.13.16", "3.3.7")
    )
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project {
         |  scala-versions = ["2.13.16", "3.3.7"]
         |  dependencies = [
         |    { dependency = "org:name:1.0", scala-filter = "2" }
         |    "org2::name2:2.0.0"
         |  ]
         |}""".stripMargin

    assertEquals(result, expected)
  }

  test("format with note, intransitive, and scala-filter uses single-line object") {
    val config = GroupConfig.Simple(
      List(AnnotatedDependency("org:name:1.0", Some("reason"), intransitive = true, scalaFilter = Some("2")))
    )
    val result = config.format(Group("my-project"))

    val expected =
      """|my-project = [
         |  { dependency = "org:name:1.0", note = "reason", intransitive = true, scala-filter = "2" }
         |]""".stripMargin

    assertEquals(result, expected)
  }

  test("format with long note and scala-filter uses multi-line object") {
    val longNote =
      "This dependency is pinned because the next major version drops support for Scala 2.12 and we still need cross-building"
    val config = GroupConfig.Simple(
      List(AnnotatedDependency("org.typelevel::cats-core:^2.10.0", Some(longNote), scalaFilter = Some("2")))
    )
    val result = config.format(Group("my-project"))

    val expected =
      s"""|my-project = [
          |  {
          |    dependency = "org.typelevel::cats-core:^2.10.0"
          |    note = "$longNote"
          |    scala-filter = "2"
          |  }
          |]""".stripMargin

    assertEquals(result, expected)
  }

  // --- parse/format round-trip tests ---

  test("parse then format round-trips simple format with intransitive") {
    val hocon =
      """|my-group = [
         |  { dependency = "org::name:=1.0.0", intransitive = true }
         |  "org2::name2:2.0.0"
         |]""".stripMargin

    val parsed    = parseGroup(hocon, "my-group")
    val formatted = parsed.map(_.format(Group("my-group")))

    assertEquals(formatted, Right(hocon))
  }

  test("parse then format round-trips simple format with notes") {
    val hocon =
      """|my-group = [
         |  { dependency = "org::name:^1.0.0", note = "Pinned to major 1" }
         |  "org2::name2:2.0.0"
         |]""".stripMargin

    val parsed    = parseGroup(hocon, "my-group")
    val formatted = parsed.map(_.format(Group("my-group")))

    assertEquals(formatted, Right(hocon))
  }

  test("parse then format round-trips simple format with scala-filter") {
    val hocon =
      """|my-group = [
         |  { dependency = "org:name:1.0", scala-filter = "2" }
         |  "org2::name2:2.0.0"
         |]""".stripMargin

    val parsed    = parseGroup(hocon, "my-group")
    val formatted = parsed.map(_.format(Group("my-group")))

    assertEquals(formatted, Right(hocon))
  }

  test("parse then format round-trips advanced format with scala-filter") {
    val hocon =
      """|my-group {
         |  scala-versions = ["2.13.16", "3.3.7"]
         |  dependencies = [
         |    { dependency = "org:name:1.0", scala-filter = "2" }
         |    "org2::name2:2.0.0"
         |  ]
         |}""".stripMargin

    val parsed    = parseGroup(hocon, "my-group")
    val formatted = parsed.map(_.format(Group("my-group")))

    assertEquals(formatted, Right(hocon))
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

  // --- javaVersion property tests ---

  test("Simple.javaVersion returns None") {
    val config = GroupConfig.Simple(List("dep1"))

    assertEquals(config.javaVersion, None)
  }

  test("Advanced.javaVersion returns the configured version") {
    val config = GroupConfig.Advanced(List("dep1"), Nil, Some("25"))

    assertEquals(config.javaVersion, Some("25"))
  }

  test("Advanced.javaVersion returns None when not configured") {
    val config = GroupConfig.Advanced(List("dep1"), Nil)

    assertEquals(config.javaVersion, None)
  }

  // --- parse/format round-trip tests for java-version ---

  test("parse then format round-trips advanced format with java-version") {
    val hocon =
      """|my-group {
         |  java-version = "25"
         |  dependencies = [
         |    "dep1"
         |    "dep2"
         |  ]
         |}""".stripMargin

    val parsed    = parseGroup(hocon, "my-group")
    val formatted = parsed.map(_.format(Group("my-group")))

    assertEquals(formatted, Right(hocon))
  }

  test("parse then format round-trips advanced format with java-version and scala-versions") {
    val hocon =
      """|my-group {
         |  java-version = "25"
         |  scala-versions = ["2.13.12", "3.3.1"]
         |  dependencies = [
         |    "dep1"
         |  ]
         |}""".stripMargin

    val parsed    = parseGroup(hocon, "my-group")
    val formatted = parsed.map(_.format(Group("my-group")))

    assertEquals(formatted, Right(hocon))
  }

  // --- dependencyLines property tests ---

  test("Simple.dependencyLines returns the lines") {
    val config = GroupConfig.Simple(List("dep1", dep("dep2", "a note")))

    assertEquals(config.dependencyLines, List("dep1", "dep2"))
  }

  test("Advanced.dependencyLines returns the lines") {
    val config = GroupConfig.Advanced(List("dep1", dep("dep2", "a note")), List("2.13.12"))

    assertEquals(config.dependencyLines, List("dep1", "dep2"))
  }

  // --- sbt-build cannot carry scala/java settings ---

  test("parse rejects sbt-build with scala-version") {
    val result = parseGroup("""sbt-build { scala-version = "2.13.16", dependencies = [] }""", "sbt-build")

    assert(result.isLeft, s"expected Left, got $result")
    assert(
      result.swap.exists(msg => msg.contains("scala-version") && msg.contains("common-settings")),
      s"expected migration message, got: $result"
    )
  }

  test("parse rejects sbt-build with scala-versions") {
    val result =
      parseGroup("""sbt-build { scala-versions = ["2.13.16"], dependencies = [] }""", "sbt-build")

    assert(result.isLeft, s"expected Left, got $result")
    assert(
      result.swap.exists(msg => msg.contains("scala-versions") && msg.contains("common-settings")),
      s"expected migration message, got: $result"
    )
  }

  test("parse rejects sbt-build with java-version") {
    val result = parseGroup("""sbt-build { java-version = "17", dependencies = [] }""", "sbt-build")

    assert(result.isLeft, s"expected Left, got $result")
    assert(
      result.swap.exists(msg => msg.contains("java-version") && msg.contains("common-settings")),
      s"expected migration message, got: $result"
    )
  }

  test("parse accepts sbt-build with dependencies only (simple format)") {
    val result = parseGroup("""sbt-build = ["org:plugin:1.0.0:sbt-plugin"]""", "sbt-build")

    assertEquals(result, Right(GroupConfig.Simple(List("org:plugin:1.0.0:sbt-plugin"))))
  }

  test("parse accepts sbt-build with dependencies only (advanced format)") {
    val result = parseGroup("""sbt-build { dependencies = ["org:plugin:1.0.0:sbt-plugin"] }""", "sbt-build")

    assertEquals(result, Right(GroupConfig.Advanced(List("org:plugin:1.0.0:sbt-plugin"))))
  }

  test("parse accepts common-settings with scala-version") {
    val result =
      parseGroup("""common-settings { scala-version = "2.13.16" }""", "common-settings")

    assertEquals(result, Right(GroupConfig.Advanced(Nil, List("2.13.16"))))
  }

}
