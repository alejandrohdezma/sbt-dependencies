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

class SbtDependenciesGotoDeclarationHandlerSuite extends munit.FunSuite {

  test("projectNameAt resolves a plain project name under the offset") {
    val result = SbtDependenciesGotoDeclarationHandler.projectNameAt(buildSbt, buildSbt.indexOf("core") + 1)

    val expected = Some("core")

    assertEquals(result, expected)
  }

  test("projectNameAt resolves a backquoted project name") {
    val result = SbtDependenciesGotoDeclarationHandler.projectNameAt(buildSbt, buildSbt.indexOf("my-api") + 1)

    val expected = Some("my-api")

    assertEquals(result, expected)
  }

  test("projectNameAt returns None outside the name") {
    val result = SbtDependenciesGotoDeclarationHandler.projectNameAt(buildSbt, buildSbt.indexOf("lazy val core"))

    val expected = None

    assertEquals(result, expected)
  }

  test("projectDefinitionOffset points at the name of the definition") {
    val result = SbtDependenciesGotoDeclarationHandler.projectDefinitionOffset(buildSbt, "core")

    val expected = Some(buildSbt.indexOf("core"))

    assertEquals(result, expected)
  }

  test("projectDefinitionOffset points at a backquoted name") {
    val result = SbtDependenciesGotoDeclarationHandler.projectDefinitionOffset(buildSbt, "my-api")

    val expected = Some(buildSbt.indexOf("my-api"))

    assertEquals(result, expected)
  }

  test("projectDefinitionOffset returns None for unknown projects") {
    val result = SbtDependenciesGotoDeclarationHandler.projectDefinitionOffset(buildSbt, "missing")

    val expected = None

    assertEquals(result, expected)
  }

  private lazy val buildSbt =
    """ThisBuild / scalaVersion := "3.3.7"
      |
      |lazy val core = project.in(file("modules/core"))
      |
      |lazy val `my-api` = project
      |  .dependsOn(core)
      |""".stripMargin

}
