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

package com.alejandrohdezma.sbt.dependencies.intellij.diagnostics

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span
import com.intellij.openapi.util.TextRange

class SbtDependenciesAnnotatorSuite extends munit.FunSuite {

  test("visibleRange keeps a regular span as-is") {
    val result = SbtDependenciesAnnotator.visibleRange(Span(3, 10), textLength = 20)

    val expected = Some(new TextRange(3, 10))

    assertEquals(result, expected)
  }

  test("visibleRange widens a zero-width span one character to the right") {
    val result = SbtDependenciesAnnotator.visibleRange(Span(5, 5), textLength = 20)

    val expected = Some(new TextRange(5, 6))

    assertEquals(result, expected)
  }

  test("visibleRange clamps a span that outruns the text") {
    val result = SbtDependenciesAnnotator.visibleRange(Span(5, 30), textLength = 10)

    val expected = Some(new TextRange(5, 10))

    assertEquals(result, expected)
  }

  test("visibleRange drops a span past the end of the text") {
    val result = SbtDependenciesAnnotator.visibleRange(Span(10, 10), textLength = 10)

    val expected = None

    assertEquals(result, expected)
  }

  test("removable accepts empty-string and duplicate diagnostics only") {
    val messages = List(
      "Empty dependency string"                                   -> true,
      "duplicate dependency entry: com.example:example (compile)" -> true,
      "object entry must have a 'dependency' field"               -> false
    )

    messages.foreach { case (message, expected) =>
      assertEquals(SbtDependenciesAnnotator.removable(message), expected, message)
    }
  }

  test("enclosingEntry resolves the entry containing a span") {
    val text =
      """example = [
        |  "com.example::example:1.0.0"
        |  "com.example::example:2.0.0"
        |]
        |""".stripMargin

    val document = DependenciesDocument.parse(text)

    val duplicate = text.indexOf("com.example::example:2.0.0")

    val result = SbtDependenciesAnnotator.enclosingEntry(document, Span(duplicate, duplicate + 26))

    val expected = Some(Span(duplicate - 1, duplicate + 27))

    assertEquals(result, expected)
    assertEquals(result.map(span => text.substring(span.start, span.end)), Some("\"com.example::example:2.0.0\""))
  }

  test("enclosingEntry returns None for a span outside any entry") {
    val text =
      """example = [
        |  "com.example::example:1.0.0"
        |]
        |""".stripMargin

    val document = DependenciesDocument.parse(text)

    val result = SbtDependenciesAnnotator.enclosingEntry(document, Span(0, 7))

    val expected = None

    assertEquals(result, expected)
  }

  test("deletionRange expands an entry alone on its line to the whole line") {
    val text =
      """example = [
        |  "com.example::example:1.0.0"
        |  "com.example::example:2.0.0"
        |]
        |""".stripMargin

    val entry = spanOf(text, "\"com.example::example:2.0.0\"")

    val result = RemoveEntryQuickFix.deletionRange(text, entry)

    val expected = text.replace("  \"com.example::example:2.0.0\"\n", "")

    assertEquals(remove(text, result), expected)
  }

  test("deletionRange expands a multi-line object entry to its full lines") {
    val text =
      """example = [
        |  {
        |    dependency = ""
        |    note = "why"
        |  }
        |]
        |""".stripMargin

    val entry = Span(text.indexOf("{"), text.indexOf("}") + 1)

    val result = RemoveEntryQuickFix.deletionRange(text, entry)

    val expected =
      """example = [
        |]
        |""".stripMargin

    assertEquals(remove(text, result), expected)
  }

  test("deletionRange keeps to the entry when it shares a line") {
    val text = """example = [ "com.example::a:1.0.0" "com.example::a:1.0.0" ]"""

    val entry = Span(text.lastIndexOf("\"com.example::a:1.0.0\""), text.lastIndexOf("\"com.example::a:1.0.0\"") + 22)

    val result = RemoveEntryQuickFix.deletionRange(text, entry)

    val expected = entry

    assertEquals(result, expected)
  }

  test("deletionRange handles a last line without trailing newline") {
    val text = "example = [\n  \"\""

    val entry = spanOf(text, "\"\"")

    val result = RemoveEntryQuickFix.deletionRange(text, entry)

    val expected = "example = [\n"

    assertEquals(remove(text, result), expected)
  }

  private def spanOf(text: String, fragment: String): Span = {
    val start = text.indexOf(fragment)

    Span(start, start + fragment.length)
  }

  private def remove(text: String, span: Span): String =
    text.substring(0, span.start) + text.substring(span.end)

}
