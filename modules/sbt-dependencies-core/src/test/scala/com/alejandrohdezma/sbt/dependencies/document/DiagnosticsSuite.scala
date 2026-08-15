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

package com.alejandrohdezma.sbt.dependencies.document

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span

/** Tests for `Diagnostics.check`, the positioned validation of a `dependencies.conf` document. */
class DiagnosticsSuite extends munit.FunSuite {

  test("returns no diagnostics for valid dependency lines") {
    val text =
      """|my-group = [
         |  "org.typelevel::cats-core:2.10.0"
         |  "com.typesafe:config:1.4.3"
         |  "io.circe::circe-core:=0.14.6"
         |  "org.http4s::http4s-core:~0.23.25"
         |  "co.fs2::fs2-core:^3.9.0"
         |  "com.disneystreaming.smithy4s::smithy4s-core:{{smithy4sVersion}}"
         |  "org.scalameta::munit:1.0.0:test"
         |  "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"
         |  "com.fasterxml.jackson.core:jackson-databind:*"
         |  "org.junit.jupiter:junit-jupiter-api:*:test"
         |]""".stripMargin

    val result = check(text)

    assertEquals(result, List.empty[Diagnostic])
  }

  test("flags an empty dependency string") {
    val text =
      """|my-group = [
         |  ""
         |]""".stripMargin

    val result = check(text).map(diagnostic => (diagnostic.message, diagnostic.severity))

    assertEquals(result, List(("Empty dependency string", Diagnostic.Severity.Error)))
  }

  test("flags an unclosed variable reference") {
    val text =
      """|my-group = [
         |  "org::art:{{broken"
         |]""".stripMargin

    val result = check(text).map(_.message)

    assertEquals(result, List("Unclosed variable reference: missing \"}}\""))
  }

  test("flags a dependency without a separator") {
    val text =
      """|my-group = [
         |  "just-a-word"
         |]""".stripMargin

    val result = check(text).map(diagnostic => (diagnostic.message, slice(text, diagnostic.span)))

    assertEquals(result, List(("just-a-word is not a valid dependency", "just-a-word")))
  }

  test("flags a dependency missing a version") {
    val text =
      """|my-group = [
         |  "org.typelevel::cats-core"
         |]""".stripMargin

    val result = check(text).map(_.message)

    assertEquals(result, List("org.typelevel::cats-core is missing a version"))
  }

  test("flags an invalid version marker") {
    val text =
      """|my-group = [
         |  "org::art:!2.0"
         |]""".stripMargin

    val result = check(text).map(_.message)

    assertEquals(result, List("org::art:!2.0 is not a valid dependency"))
  }

  test("only flags the invalid dependency among valid ones") {
    val text =
      """|my-group = [
         |  "org.typelevel::cats-core:2.10.0"
         |  "bad"
         |  "com.typesafe:config:1.4.3"
         |]""".stripMargin

    val result = check(text).map(diagnostic => (slice(text, diagnostic.span), diagnostic.severity))

    assertEquals(result, List(("bad", Diagnostic.Severity.Error)))
  }

  test("flags invalid dependencies inside an advanced group") {
    val text =
      """|my-group {
         |  dependencies = [
         |    "bad"
         |  ]
         |}""".stripMargin

    val result = check(text).map(_.message)

    assertEquals(result, List("bad is not a valid dependency"))
  }

  test("does not validate non-dependency arrays in advanced groups") {
    val text =
      """|my-group {
         |  scala-versions = ["~2.13.12"]
         |  other-field = ["not-a-dep"]
         |}""".stripMargin

    val result = check(text)

    assertEquals(result, List.empty[Diagnostic])
  }

  test("does not validate strings outside any group") {
    val text = """"just-a-word""""

    val result = check(text)

    assertEquals(result, List.empty[Diagnostic])
  }

  test("ignores dependencies hidden by comments") {
    val text =
      """|/* my-group = [
         |  "bad"
         |] */
         |real-group = [
         |  // "also-bad"
         |  "org.typelevel::cats-core:2.10.0"
         |]""".stripMargin

    val result = check(text)

    assertEquals(result, List.empty[Diagnostic])
  }

  test("flags '*' with the bom configuration") {
    val text =
      """|my-group = [
         |  "com.example:my-bom:*:bom"
         |]""".stripMargin

    val result = check(text).map(_.message)

    val expected = List(
      "com.example:my-bom declares version '*' with the 'bom' configuration — " +
        "a BOM coordinate cannot take its version from a BOM."
    )

    assertEquals(result, expected)
  }

  test("flags '*' with the sbt-plugin configuration") {
    val text =
      """|my-group = [
         |  "ch.epfl.scala:sbt-scalafix:*:sbt-plugin"
         |]""".stripMargin

    val result = check(text).map(_.message)

    val expected = List(
      "ch.epfl.scala:sbt-scalafix declares version '*' with the 'sbt-plugin' configuration — " +
        "BOMs cannot pin sbt plugin coordinates."
    )

    assertEquals(result, expected)
  }

  test("flags '*' combined with cross-version full") {
    val text =
      """|my-group = [
         |  { dependency = "org::art:*", cross-version = "full" }
         |]""".stripMargin

    val result = check(text).map(_.message)

    val expected = List(
      "Version '*' on org:art cannot be combined with cross-version = 'full' — " +
        "only 'binary' and 'disabled' are supported when the version comes from a BOM."
    )

    assertEquals(result, expected)
  }

  test("accepts '*' combined with cross-version binary or disabled") {
    List("binary", "disabled").foreach { keyword =>
      val text =
        s"""|my-group = [
            |  { dependency = "org::art:*", cross-version = "$keyword" }
            |]""".stripMargin

      val result = check(text)

      assertEquals(result, List.empty[Diagnostic])
    }
  }

  test("flags a variable combined with cross-version full") {
    val text =
      """|my-group = [
         |  { dependency = "org::art:{{v}}", cross-version = "full" }
         |]""".stripMargin

    val result = check(text).map(diagnostic => (diagnostic.message, slice(text, diagnostic.span)))

    val expected = List(
      (
        "Variable '{{v}}' on org:art cannot be combined with cross-version = 'full' — " +
          "only 'binary' and 'disabled' are supported when the version is a variable.",
        "org::art:{{v}}"
      )
    )

    assertEquals(result, expected)
  }

  test("accepts a variable combined with cross-version binary or disabled") {
    List("binary", "disabled").foreach { keyword =>
      val text =
        s"""|my-group = [
            |  { dependency = "org::art:{{v}}", cross-version = "$keyword" }
            |]""".stripMargin

      val result = check(text)

      assertEquals(result, List.empty[Diagnostic])
    }
  }

  test("flags an object entry without a dependency field") {
    val text =
      """|my-group = [
         |  { note = "missing dep" }
         |]""".stripMargin

    val result = check(text).map(diagnostic => (diagnostic.message, slice(text, diagnostic.span)))

    val expected = List(("object entry must have a 'dependency' field", """{ note = "missing dep" }"""))

    assertEquals(result, expected)
  }

  test("flags an object entry without annotations") {
    val text =
      """|my-group = [
         |  { dependency = "org.typelevel::cats-core:^2.10.0" }
         |]""".stripMargin

    val result = check(text).map(_.message)

    val expected = List("object entry must have a 'note', 'intransitive', 'scala-filter', or 'cross-version' field")

    assertEquals(result, expected)
  }

  test("accepts object entries with each annotation") {
    val annotations = List(
      """note = "reason"""",
      "intransitive = true",
      """scala-filter = "2.13"""",
      """cross-version = "disabled""""
    )

    annotations.foreach { annotation =>
      val text =
        s"""|my-group = [
            |  { dependency = "org:art:1.0.0", $annotation }
            |]""".stripMargin

      val result = check(text)

      assertEquals(result, List.empty[Diagnostic])
    }
  }

  test("validates the dependency value inside an object entry") {
    val text =
      """|my-group = [
         |  { dependency = "bad", note = "reason" }
         |]""".stripMargin

    val result = check(text).map(diagnostic => (diagnostic.message, slice(text, diagnostic.span)))

    assertEquals(result, List(("bad is not a valid dependency", "bad")))
  }

  test("flags an invalid cross-version value") {
    val text =
      """|my-group = [
         |  { dependency = "org::art:1.0.0", cross-version = "bogus" }
         |]""".stripMargin

    val result = check(text).map(diagnostic => (diagnostic.message, slice(text, diagnostic.span)))

    val expected = List(
      ("'cross-version' must be one of full, binary, patch, disabled, got 'bogus'", "bogus")
    )

    assertEquals(result, expected)
  }

  test("accepts every legal cross-version keyword") {
    List("full", "binary", "patch", "disabled").foreach { keyword =>
      val text =
        s"""|my-group = [
            |  { dependency = "org::art:1.0.0", cross-version = "$keyword" }
            |]""".stripMargin

      val result = check(text)

      assertEquals(result, List.empty[Diagnostic])
    }
  }

  test("flags multi-line objects missing a dependency field") {
    val text =
      """|my-group = [
         |  {
         |    note = "missing dep"
         |  }
         |]""".stripMargin

    val result = check(text).map(_.message)

    assertEquals(result, List("object entry must have a 'dependency' field"))
  }

  test("flags multi-line objects missing annotations") {
    val text =
      """|my-group = [
         |  {
         |    dependency = "org.typelevel::cats-core:^2.10.0"
         |  }
         |]""".stripMargin

    val result = check(text).map(_.message)

    val expected = List("object entry must have a 'note', 'intransitive', 'scala-filter', or 'cross-version' field")

    assertEquals(result, expected)
  }

  test("accepts a valid multi-line object") {
    val text =
      """|my-group = [
         |  {
         |    dependency = "org.http4s::http4s-core:=0.23.3"
         |    note = "Uses internal API"
         |    intransitive = true
         |  }
         |]""".stripMargin

    val result = check(text)

    assertEquals(result, List.empty[Diagnostic])
  }

  test("warns on a duplicated dependency") {
    val text =
      """|my-group = [
         |  "org.typelevel::cats-core:2.10.0"
         |  "org.typelevel::cats-core:^2.11.0"
         |]""".stripMargin

    val result = check(text).map { diagnostic =>
      (diagnostic.message, diagnostic.severity, slice(text, diagnostic.span))
    }

    val expected = List(
      (
        "duplicate dependency entry: org.typelevel:cats-core (compile)",
        Diagnostic.Severity.Warning,
        "org.typelevel::cats-core:^2.11.0"
      )
    )

    assertEquals(result, expected)
  }

  test("warns on every repetition beyond the first") {
    val text =
      """|my-group = [
         |  "org.typelevel::cats-core:2.10.0"
         |  "org.typelevel::cats-core:^2.11.0"
         |  "org.typelevel::cats-core:~2.12.0"
         |]""".stripMargin

    val result = check(text).map(diagnostic => slice(text, diagnostic.span))

    assertEquals(result, List("org.typelevel::cats-core:^2.11.0", "org.typelevel::cats-core:~2.12.0"))
  }

  test("does not warn for the same dependency in different groups") {
    val text =
      """|group-a = [
         |  "org.typelevel::cats-core:2.10.0"
         |]
         |group-b = [
         |  "org.typelevel::cats-core:2.10.0"
         |]""".stripMargin

    val result = check(text)

    assertEquals(result, List.empty[Diagnostic])
  }

  test("treats an explicit compile configuration as an absent one") {
    val text =
      """|my-group = [
         |  "org.typelevel::cats-core:2.10.0:compile"
         |  "org.typelevel::cats-core:2.10.0"
         |]""".stripMargin

    val result = check(text).map(diagnostic => (diagnostic.severity, slice(text, diagnostic.span)))

    val expected = List(
      (Diagnostic.Severity.Warning: Diagnostic.Severity, "org.typelevel::cats-core:2.10.0")
    )

    assertEquals(result, expected)
  }

  test("does not warn for the same artifact in different configurations") {
    val text =
      """|my-group = [
         |  "com.google.protobuf:protobuf-java:3.25.1"
         |  "com.google.protobuf:protobuf-java:3.25.1:protobuf"
         |  "com.google.protobuf:protobuf-java:3.25.1:bom"
         |  "org.scalameta::munit:1.0.0"
         |  "org.scalameta::munit:1.0.0:test"
         |]""".stripMargin

    val result = check(text)

    assertEquals(result, List.empty[Diagnostic])
  }

  test("warns for the same artifact repeated in the same configuration") {
    val text =
      """|my-group = [
         |  "com.google.protobuf:protobuf-java:3.25.1:protobuf"
         |  "com.google.protobuf:protobuf-java:3.25.2:protobuf"
         |]""".stripMargin

    val result = check(text).map(_.message)

    assertEquals(result, List("duplicate dependency entry: com.google.protobuf:protobuf-java (protobuf)"))
  }

  test("keys duplicates on organization and name regardless of separator") {
    val text =
      """|my-group = [
         |  "com.typesafe:config:1.4.3"
         |  "com.typesafe::config:1.0.0"
         |]""".stripMargin

    val result = check(text).map(diagnostic => (diagnostic.message, diagnostic.severity))

    val expected = List(
      ("duplicate dependency entry: com.typesafe:config (compile)", Diagnostic.Severity.Warning: Diagnostic.Severity)
    )

    assertEquals(result, expected)
  }

  test("detects duplicates across string and object entries") {
    val text =
      """|my-group = [
         |  "org.typelevel::cats-core:2.10.0"
         |  { dependency = "org.typelevel::cats-core:^2.11.0", note = "pinned" }
         |]""".stripMargin

    val result = check(text).map { diagnostic =>
      (diagnostic.message, diagnostic.severity, slice(text, diagnostic.span))
    }

    val expected = List(
      (
        "duplicate dependency entry: org.typelevel:cats-core (compile)",
        Diagnostic.Severity.Warning,
        "org.typelevel::cats-core:^2.11.0"
      )
    )

    assertEquals(result, expected)
  }

  test("resets state across sequential groups") {
    val text =
      """|group-a = [
         |  "org.typelevel::cats-core:2.10.0"
         |]
         |group-b = [
         |  "bad"
         |]""".stripMargin

    val result = check(text).map(diagnostic => slice(text, diagnostic.span))

    assertEquals(result, List("bad"))
  }

  test("validates entries on the closing bracket line") {
    val text =
      """|my-group = [
         |  "bad"]""".stripMargin

    val result = check(text).map(_.message)

    assertEquals(result, List("bad is not a valid dependency"))
  }

  test("accepts empty groups") {
    val text =
      """|my-group = []
         |
         |sbt-build {
         |  scala-version = "~2.12.21"
         |  dependencies = []
         |}""".stripMargin

    val result = check(text)

    assertEquals(result, List.empty[Diagnostic])
  }

  private def check(text: String): List[Diagnostic] = Diagnostics.check(DependenciesDocument.parse(text))

  private def slice(text: String, span: Span): String = text.substring(span.start, span.end)

}
