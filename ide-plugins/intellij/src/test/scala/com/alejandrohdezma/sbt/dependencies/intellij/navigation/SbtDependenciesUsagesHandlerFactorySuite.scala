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

package com.alejandrohdezma.sbt.dependencies.intellij.navigation

import com.alejandrohdezma.sbt.dependencies.intellij.diagnostics.RenameVariableQuickFix

class SbtDependenciesUsagesHandlerFactorySuite extends munit.FunSuite {

  test("usages finds every reference of the variable under the caret") {
    val result = SbtDependenciesUsagesHandlerFactory
      .usages(text, text.indexOf("{{circeVersion}}") + 3)
      .map(span => text.substring(span.start, span.end))

    val expected = List("{{circeVersion}}", "{{circeVersion}}")

    assertEquals(result, expected)
  }

  test("usages finds every entry of the dependency under the caret") {
    val result = SbtDependenciesUsagesHandlerFactory
      .usages(text, text.indexOf("org.typelevel"))
      .map(span => text.substring(span.start, span.end))

    val expected = List("org.typelevel::cats-core", "org.typelevel::cats-core")

    assertEquals(result, expected)
  }

  test("usages returns nothing outside variables and dependencies") {
    val result = SbtDependenciesUsagesHandlerFactory.usages(text, text.indexOf("api ="))

    val expected = Nil

    assertEquals(result, expected)
  }

  test("sanitize strips braces and rejects invalid names") {
    assertEquals(RenameVariableQuickFix.sanitize("{{catsVersion}}"), Some("catsVersion"))
    assertEquals(RenameVariableQuickFix.sanitize("  newName "), Some("newName"))
    assertEquals(RenameVariableQuickFix.sanitize("not a name"), None)
  }

  test("rename replaces every reference of the variable") {
    val result = RenameVariableQuickFix.rename(text, "circeVersion", "circeV")

    assertEquals(result.contains("{{circeVersion}}"), false)
    assertEquals(result.split("\\{\\{circeV\\}\\}", -1).length - 1, 2)
  }

  private lazy val text =
    """api = [
      |  "io.circe::circe-core:{{circeVersion}}"
      |  "io.circe::circe-parser:{{circeVersion}}"
      |  "org.typelevel::cats-core:2.10.0"
      |]
      |
      |core = [
      |  "org.typelevel::cats-core:2.10.0:test"
      |]
      |""".stripMargin

}
