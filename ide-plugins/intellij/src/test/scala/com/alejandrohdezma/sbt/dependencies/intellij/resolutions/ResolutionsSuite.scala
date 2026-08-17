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

package com.alejandrohdezma.sbt.dependencies.intellij.resolutions

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span
import com.alejandrohdezma.sbt.dependencies.intellij.diagnostics.SbtDependenciesAnnotator

class ResolutionsSuite extends munit.FunSuite {

  test("parse reads a version-1 dump") {
    val result = Resolutions.parse(mainDump)

    assertEquals(result.map(_.sourceHash), Some(Some("abc123")))
    assertEquals(result.map(_.boms.size), Some(2))
    assertEquals(result.map(_.projects.keySet), Some(Set("myproject")))
  }

  test("parse rejects unsupported versions and malformed JSON") {
    assertEquals(Resolutions.parse("""{"version": 2, "boms": {}, "projects": {}}"""), None)
    assertEquals(Resolutions.parse("not json"), None)
  }

  test("resolveWildcard resolves a plain (Java) * against the exact artifact name") {
    val result = lookup.resolveWildcard("myproject", "com.fasterxml.jackson.core", "jackson-databind", isCross = false)

    val expected = Some(Resolutions.Pin("2.17.0", "com.fasterxml.jackson", "jackson-bom", "2.17.0"))

    assertEquals(result, expected)
  }

  test("resolveWildcard resolves a cross (Scala) * against the Scala-suffixed artifact name") {
    val result = lookup.resolveWildcard("myproject", "org.typelevel", "cats-core", isCross = true)

    assertEquals(result.map(_.version), Some("2.10.0"))
  }

  test("resolveWildcard applies first-BOM-wins precedence") {
    val result = lookup.resolveWildcard("myproject", "com.shared", "artifact", isCross = false)

    assertEquals(result.map(_.version), Some("1.0.0"))
    assertEquals(result.map(_.bomName), Some("jackson-bom"))
  }

  test("resolveWildcard routes the sbt-build group to the meta dump") {
    val result = lookup.resolveWildcard("sbt-build", "com.example", "sbt-thing", isCross = false)

    assertEquals(result.map(_.version), Some("0.4.0"))
  }

  test("resolveVariable matches organization, name and cross") {
    val result = lookup.resolveVariable("myproject", "io.circe", "circe-core", isCross = true)

    val expected =
      Some(Resolutions.VariableResolution("io.circe", "circe-core", cross = true, "circeVersion", "0.14.10"))

    assertEquals(result, expected)
  }

  test("decorate positions ghost text after the closing quote of resolved entries") {
    val text =
      """myproject = [
        |  "com.fasterxml.jackson.core:jackson-databind:*"
        |  "io.circe::circe-core:{{circeVersion}}"
        |  "org.typelevel::cats-effect:3.6.1"
        |]
        |""".stripMargin

    val result = SbtDependenciesResolvedVersionInlays.decorate(text, lookup)

    val expected = List(
      text.indexOf("jackson-databind:*\"") + "jackson-databind:*\"".length -> " = 2.17.0",
      text.indexOf("{{circeVersion}}\"") + "{{circeVersion}}\"".length     -> " = 0.14.10"
    )

    assertEquals(result, expected)
  }

  test("decorate marks stale lookups") {
    val stale = new Resolutions.Lookup(Resolutions.parse(mainDump), None, stale = true)

    val text =
      """myproject = [
        |  "org.typelevel::cats-core:*"
        |]
        |""".stripMargin

    val result = SbtDependenciesResolvedVersionInlays.decorate(text, stale)

    assertEquals(result.map { case (_, text) => text }, List(" = 2.10.0 (stale)"))
  }

  test("rewrites offers materializing * and switching managed versions") {
    val text =
      """myproject = [
        |  "com.fasterxml.jackson.core:jackson-databind:*"
        |  "org.typelevel::cats-core:2.9.0"
        |  "io.circe::circe-core:{{circeVersion}}"
        |  "com.unmanaged:artifact:1.0.0"
        |]
        |""".stripMargin

    val document = DependenciesDocument.parse(text)

    val result = SbtDependenciesAnnotator.rewrites(document, Some(lookup))

    val expected = List(
      SbtDependenciesAnnotator.Rewrite(
        spanOf(text, "jackson-databind:", "*"),
        "2.17.0",
        "Replace * with resolved version 2.17.0"
      ),
      SbtDependenciesAnnotator.Rewrite(
        spanOf(text, "cats-core:", "2.9.0"),
        "*",
        "Replace 2.9.0 with * (managed by jackson-bom)",
        Some(
          "org.typelevel:cats-core is managed by com.fasterxml.jackson:jackson-bom:2.17.0 — " +
            "the version can be replaced with *"
        )
      )
    )

    assertEquals(result, expected)
  }

  test("rewrites skips bom and sbt-plugin configurations") {
    val text =
      """myproject = [
        |  "org.typelevel::cats-core:2.9.0:bom"
        |]
        |""".stripMargin

    val document = DependenciesDocument.parse(text)

    val result = SbtDependenciesAnnotator.rewrites(document, Some(lookup))

    val expected = Nil

    assertEquals(result, expected)
  }

  test("rewrites survive a stale dump") {
    val stale = new Resolutions.Lookup(Resolutions.parse(mainDump), None, stale = true)

    val text =
      """myproject = [
        |  "com.fasterxml.jackson.core:jackson-databind:*"
        |]
        |""".stripMargin

    val document = DependenciesDocument.parse(text)

    val result = SbtDependenciesAnnotator.rewrites(document, Some(stale))

    assertEquals(result.map(_.replacement), List("2.17.0"))
  }

  private def spanOf(text: String, prefix: String, fragment: String) = {
    val start = text.indexOf(prefix) + prefix.length

    Span(start, start + fragment.length)
  }

  private lazy val mainDump =
    """{
      |  "version": 1,
      |  "sourceHash": "abc123",
      |  "boms": {
      |    "com.fasterxml.jackson:jackson-bom:2.17.0@2.13": {
      |      "organization": "com.fasterxml.jackson", "name": "jackson-bom", "version": "2.17.0",
      |      "entries": [
      |        {"organization": "com.fasterxml.jackson.core", "name": "jackson-databind", "version": "2.17.0"},
      |        {"organization": "org.typelevel", "name": "cats-core_2.13", "version": "2.10.0"},
      |        {"organization": "com.shared", "name": "artifact", "version": "1.0.0"}
      |      ]
      |    },
      |    "com.other:other-bom:9.9.9@2.13": {
      |      "organization": "com.other", "name": "other-bom", "version": "9.9.9",
      |      "entries": [{"organization": "com.shared", "name": "artifact", "version": "9.9.9"}]
      |    }
      |  },
      |  "projects": {
      |    "myproject": {
      |      "scalaBinaryVersions": ["2.13"],
      |      "boms": ["com.fasterxml.jackson:jackson-bom:2.17.0@2.13", "com.other:other-bom:9.9.9@2.13"],
      |      "variables": [
      |        {"organization": "io.circe", "name": "circe-core", "cross": true, "variable": "circeVersion", "version": "0.14.10"}
      |      ]
      |    }
      |  }
      |}
      |""".stripMargin

  private lazy val metaDump =
    """{
      |  "version": 1,
      |  "boms": {
      |    "com.example:plugin-bom:1.0.0@2.12": {
      |      "organization": "com.example", "name": "plugin-bom", "version": "1.0.0",
      |      "entries": [{"organization": "com.example", "name": "sbt-thing", "version": "0.4.0"}]
      |    }
      |  },
      |  "projects": {
      |    "sbt-build": {
      |      "scalaBinaryVersions": ["2.12"],
      |      "boms": ["com.example:plugin-bom:1.0.0@2.12"],
      |      "variables": []
      |    }
      |  }
      |}
      |""".stripMargin

  private lazy val lookup =
    new Resolutions.Lookup(Resolutions.parse(mainDump), Resolutions.parse(metaDump), stale = false)

}
