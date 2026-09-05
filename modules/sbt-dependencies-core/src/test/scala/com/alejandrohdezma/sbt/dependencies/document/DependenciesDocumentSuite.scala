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

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Entry
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Group
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span

/** Tests for `DependenciesDocument.parse`, the positioned and lenient view of a `dependencies.conf` document. */
class DependenciesDocumentSuite extends munit.FunSuite {

  test("parses a simple group into its name, kind and dependency lines") {
    val text =
      """|my-group = [
         |  "org.typelevel::cats-core:2.0.0"
         |  "com.typesafe:config:1.4.3"
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.map { group =>
      (group.name, group.kind, group.entries.flatMap(_.dependency).map(_.value))
    }

    val expected = List(
      ("my-group", Group.Kind.Simple, List("org.typelevel::cats-core:2.0.0", "com.typesafe:config:1.4.3"))
    )

    assertEquals(result, expected)
  }

  test("returns groups in source order") {
    val text =
      """|simple = [
         |  "a:b:1.0.0"
         |]
         |
         |advanced {
         |  dependencies = [
         |    "c:d:2.0.0"
         |  ]
         |}""".stripMargin

    val result = DependenciesDocument.parse(text).groups.map(group => (group.name, group.kind))

    val expected = List(("simple", Group.Kind.Simple), ("advanced", Group.Kind.Advanced))

    assertEquals(result, expected)
  }

  test("positions the group name at its span") {
    val text =
      """|my-group = [
         |  "a:b:1.0.0"
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.map(group => slice(text, group.nameSpan))

    assertEquals(result, List("my-group"))
  }

  test("spans a simple group from its header to the closing bracket") {
    val text =
      """|my-group = [
         |  "a:b:1.0.0"
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.map(group => slice(text, group.span))

    assertEquals(result, List(text))
  }

  test("positions plain entries at their quoted text") {
    val text =
      """|my-group = [
         |  "org.typelevel::cats-core:2.0.0"
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).map { entry =>
      (slice(text, entry.span), entry.dependency.map(field => slice(text, field.valueSpan)))
    }

    val expected = List(("\"org.typelevel::cats-core:2.0.0\"", Some("org.typelevel::cats-core:2.0.0")))

    assertEquals(result, expected)
  }

  test("parses single-line groups") {
    val text = """my-group = ["a:b:1.0.0" "c:d:2.0.0"]"""

    val result = DependenciesDocument.parse(text).groups.map { group =>
      (slice(text, group.span), group.entries.flatMap(_.dependency).map(_.value))
    }

    val expected = List((text, List("a:b:1.0.0", "c:d:2.0.0")))

    assertEquals(result, expected)
  }

  test("parses empty groups") {
    val text =
      """|my-group = [
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.map(_.entries)

    assertEquals(result, List(List.empty[Entry]))
  }

  test("parses group names with dots and hyphens") {
    val text =
      """|my.group-name = [
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.map(_.name)

    assertEquals(result, List("my.group-name"))
  }

  test("parses an advanced group with settings and dependencies") {
    val text =
      """|api {
         |  scala-version = "3.3.6"
         |  java-version = "17"
         |  dependencies = [
         |    "org.typelevel::cats-core:2.0.0"
         |  ]
         |}""".stripMargin

    val result = DependenciesDocument.parse(text).groups.map { group =>
      (group.kind, group.settings.map(_.key), group.entries.flatMap(_.dependency).map(_.value))
    }

    val expected = List(
      (Group.Kind.Advanced, List("scala-version", "java-version"), List("org.typelevel::cats-core:2.0.0"))
    )

    assertEquals(result, expected)
  }

  test("positions setting keys at their spans") {
    val text =
      """|api {
         |  scala-version = "3.3.6"
         |}""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.settings).map { setting =>
      slice(text, setting.keySpan)
    }

    assertEquals(result, List("scala-version"))
  }

  test("parses a single-line dependencies array in an advanced group") {
    val text =
      """|api {
         |  dependencies = ["a:b:1.0.0" "c:d:2.0.0"]
         |}""".stripMargin

    val result = DependenciesDocument.parse(text).groups.map(_.entries.flatMap(_.dependency).map(_.value))

    assertEquals(result, List(List("a:b:1.0.0", "c:d:2.0.0")))
  }

  test("spans an advanced group from its header to the closing brace") {
    val text =
      """|api {
         |  scala-version = "3.3.6"
         |  dependencies = [
         |    "org:art:1.0.0"
         |  ]
         |}""".stripMargin

    val result = DependenciesDocument.parse(text).groups.map(group => slice(text, group.span))

    assertEquals(result, List(text))
  }

  test("parses a single-line object entry with a note") {
    val text =
      """|my-group = [
         |  { dependency = "org:art:1.0.0", note = "reason" }
         |]""".stripMargin

    val result =
      DependenciesDocument.parse(text).groups.flatMap(_.entries).collect { case obj: Entry.DependencyObject =>
        (obj.dependency.map(field => slice(text, field.valueSpan)), obj.note.map(_.value), obj.intransitive)
      }

    val expected = List((Some("org:art:1.0.0"), Some("reason"), false))

    assertEquals(result, expected)
  }

  test("parses intransitive in a single-line object") {
    val text =
      """|my-group = [
         |  { dependency = "org:art:1.0.0", intransitive = true }
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).collect {
      case obj: Entry.DependencyObject => obj.intransitive
    }

    assertEquals(result, List(true))
  }

  test("parses overrides in a single-line object") {
    val text =
      """|my-group = [
         |  { dependency = "org:bom:1.0.0:bom", overrides = true }
         |  { dependency = "org:art:1.0.0", note = "reason" }
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).collect {
      case obj: Entry.DependencyObject => obj.overrides
    }

    assertEquals(result, List(true, false))
  }

  test("parses overrides in a multi-line object") {
    val text =
      """|my-group = [
         |  {
         |    dependency = "org:bom:1.0.0:bom"
         |    overrides = true
         |  }
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).collect {
      case obj: Entry.DependencyObject => (obj.dependency.map(_.value), obj.overrides)
    }

    assertEquals(result, List((Some("org:bom:1.0.0:bom"), true)))
  }

  test("parses scala-filter in a single-line object") {
    val text =
      """|my-group = [
         |  { dependency = "org:art:1.0.0", scala-filter = "2.13" }
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).collect {
      case obj: Entry.DependencyObject => obj.scalaFilter.map(_.value)
    }

    assertEquals(result, List(Some("2.13")))
  }

  test("parses cross-version in a single-line object") {
    val text =
      """|my-group = [
         |  { dependency = "org.typelevel::kind-projector:0.13.3:compiler-plugin", cross-version = "full" }
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).collect {
      case obj: Entry.DependencyObject => obj.crossVersion.map(_.value)
    }

    assertEquals(result, List(Some("full")))
  }

  test("does not treat a {{variable}} inside a quoted string as an object") {
    val text =
      """|my-group = [
         |  "org::art:{{myVar}}"
         |]""".stripMargin

    val document = DependenciesDocument.parse(text)

    val result = document.groups.flatMap(_.entries).map {
      case line: Entry.DependencyLine => ("line", line.content)
      case _: Entry.DependencyObject  => ("object", "")
    }

    assertEquals(result, List(("line", "org::art:{{myVar}}")))
  }

  test("parses a multi-line object entry") {
    val text =
      """|my-group = [
         |  {
         |    dependency = "org:art:1.0.0"
         |    note = "reason"
         |    intransitive = true
         |  }
         |]""".stripMargin

    val result =
      DependenciesDocument.parse(text).groups.flatMap(_.entries).collect { case obj: Entry.DependencyObject =>
        (obj.dependency.map(_.value), obj.note.map(_.value), obj.intransitive, slice(text, obj.span))
      }

    val expectedSpan =
      """|{
         |    dependency = "org:art:1.0.0"
         |    note = "reason"
         |    intransitive = true
         |  }""".stripMargin

    val expected = List((Some("org:art:1.0.0"), Some("reason"), true, expectedSpan))

    assertEquals(result, expected)
  }

  test("parses fields on the opening line of a multi-line object") {
    val text =
      """|my-group = [
         |  { dependency = "org:art:1.0.0"
         |    note = "reason"
         |  }
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).collect {
      case obj: Entry.DependencyObject => (obj.dependency.map(_.value), obj.note.map(_.value))
    }

    assertEquals(result, List((Some("org:art:1.0.0"), Some("reason"))))
  }

  test("parses cross-version in a multi-line object") {
    val text =
      """|my-group = [
         |  {
         |    dependency = "org.typelevel::kind-projector:0.13.3:compiler-plugin"
         |    cross-version = "full"
         |  }
         |]""".stripMargin

    val result =
      DependenciesDocument.parse(text).groups.flatMap(_.entries).collect { case obj: Entry.DependencyObject =>
        (obj.dependency.map(_.value), obj.crossVersion.map(field => slice(text, field.valueSpan)))
      }

    val expected = List((Some("org.typelevel::kind-projector:0.13.3:compiler-plugin"), Some("full")))

    assertEquals(result, expected)
  }

  test("ignores dependencies commented out with //") {
    val text =
      """|my-group = [
         |  // "org:art:1.0.0"
         |  "org2:art2:2.0.0"
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).flatMap(_.dependency).map(_.value)

    assertEquals(result, List("org2:art2:2.0.0"))
  }

  test("ignores dependencies commented out with #") {
    val text =
      """|my-group = [
         |  # "org:art:1.0.0"
         |  "org2:art2:2.0.0" # trailing comment
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).flatMap(_.dependency).map(_.value)

    assertEquals(result, List("org2:art2:2.0.0"))
  }

  test("ignores dependencies inside single-line block comments") {
    val text =
      """|my-group = [
         |  /* "org:art:1.0.0" */
         |  "org2:art2:2.0.0"
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).flatMap(_.dependency).map(_.value)

    assertEquals(result, List("org2:art2:2.0.0"))
  }

  test("ignores dependencies inside block comments spanning lines") {
    val text =
      """|my-group = [
         |  /*
         |  "org:art:1.0.0"
         |  */
         |  "org2:art2:2.0.0"
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).flatMap(_.dependency).map(_.value)

    assertEquals(result, List("org2:art2:2.0.0"))
  }

  test("ignores groups inside block comments") {
    val text =
      """|/* hidden = [
         |  "org:art:1.0.0"
         |] */
         |real = [
         |  "org2:art2:2.0.0"
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.map(_.name)

    assertEquals(result, List("real"))
  }

  test("parses entries on the closing bracket line") {
    val text =
      """|my-group = [
         |  "a:b:1.0.0"
         |  "c:d:2.0.0" ]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).flatMap(_.dependency).map(_.value)

    assertEquals(result, List("a:b:1.0.0", "c:d:2.0.0"))
  }

  test("flushes an unclosed group to the end of the text") {
    val text =
      """|my-group = [
         |  "a:b:1.0.0"""".stripMargin

    val result = DependenciesDocument.parse(text).groups.map { group =>
      (group.name, group.entries.flatMap(_.dependency).map(_.value), group.span.end)
    }

    val expected = List(("my-group", List("a:b:1.0.0"), text.length))

    assertEquals(result, expected)
  }

  test("flushes an unclosed object to the end of the text") {
    val text =
      """|my-group = [
         |  {
         |    dependency = "a:b:1.0.0"""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).collect {
      case obj: Entry.DependencyObject => (obj.dependency.map(_.value), obj.span.end)
    }

    val expected = List((Some("a:b:1.0.0"), text.length))

    assertEquals(result, expected)
  }

  test("returns no groups for an empty document") {
    val result = DependenciesDocument.parse("").groups

    assertEquals(result, List.empty[Group])
  }

  test("ignores quoted strings outside any group") {
    val text = """"just-a-word""""

    val result = DependenciesDocument.parse(text).groups

    assertEquals(result, List.empty[Group])
  }

  test("includes empty strings as entries") {
    val text =
      """|my-group = [
         |  ""
         |]""".stripMargin

    val result = DependenciesDocument.parse(text).groups.flatMap(_.entries).flatMap(_.dependency).map(_.value)

    assertEquals(result, List(""))
  }

  private def slice(text: String, span: Span): String = text.substring(span.start, span.end)

}
