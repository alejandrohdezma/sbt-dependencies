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

}
