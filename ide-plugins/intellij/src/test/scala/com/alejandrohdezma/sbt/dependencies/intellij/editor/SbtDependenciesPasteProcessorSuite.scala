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

package com.alejandrohdezma.sbt.dependencies.intellij.editor

class SbtDependenciesPasteProcessorSuite extends munit.FunSuite {

  test("convertSbtDependency converts a libraryDependencies line") {
    val result =
      SbtDependenciesPasteProcessor.convertSbtDependency(
        """libraryDependencies += "org.typelevel" %% "cats-core" % "2.10.0""""
      )

    val expected = Some("org.typelevel::cats-core:2.10.0")

    assertEquals(result, expected)
  }

  test("convertSbtDependency converts a bare dependency with a named configuration") {
    val result = SbtDependenciesPasteProcessor.convertSbtDependency(""""org.scalameta" %% "munit" % "1.3.5" % Test""")

    val expected = Some("org.scalameta::munit:1.3.5:test")

    assertEquals(result, expected)
  }

  test("convertSbtDependency converts an addSbtPlugin line") {
    val result =
      SbtDependenciesPasteProcessor.convertSbtDependency(
        """addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")"""
      )

    val expected = Some("ch.epfl.scala:sbt-scalafix:0.14.7:sbt-plugin")

    assertEquals(result, expected)
  }

  test("convertSbtDependency detects sbt plugins by the artifact suffix") {
    val result =
      SbtDependenciesPasteProcessor.convertSbtDependency(""""ch.epfl.scala" % "sbt-scalafix_2.12_1.0" % "0.14.7"""")

    val expected = Some("ch.epfl.scala:sbt-scalafix:0.14.7:sbt-plugin")

    assertEquals(result, expected)
  }

  test("convertSbtDependency turns version identifiers into variable references") {
    val result = SbtDependenciesPasteProcessor.convertSbtDependency(""""org.typelevel" %% "cats-core" % catsVersion""")

    val expected = Some("org.typelevel::cats-core:{{catsVersion}}")

    assertEquals(result, expected)
  }

  test("convertSbtDependency returns None on non-dependency text") {
    val result = SbtDependenciesPasteProcessor.convertSbtDependency("val x = 1")

    val expected = None

    assertEquals(result, expected)
  }

  test("convertPaste converts every dependency line, skipping comments and blanks") {
    val text =
      """// from mvnrepository
        |libraryDependencies += "org.typelevel" %% "cats-core" % "2.10.0"
        |
        |# another one
        |"org.scalameta" %% "munit" % "1.3.5" % Test
        |""".stripMargin

    val result = SbtDependenciesPasteProcessor.convertPaste(text)

    val expected = Some(
      """"org.typelevel::cats-core:2.10.0"
        |"org.scalameta::munit:1.3.5:test"""".stripMargin
    )

    assertEquals(result, expected)
  }

  test("convertPaste returns None when nothing matches") {
    val result = SbtDependenciesPasteProcessor.convertPaste("\"org.typelevel::cats-core:2.10.0\"")

    val expected = None

    assertEquals(result, expected)
  }

}
