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

class SbtDependenciesFoldingBuilderSuite extends munit.FunSuite {

  test("foldings hides the object syntax around the dependency string") {
    val text =
      """example = [
        |  { dependency = "com.typesafe:config:=1.4.5", note = "Pinned to the sbt version" }
        |]
        |""".stripMargin

    val result = SbtDependenciesFoldingBuilder.foldings(text).map(folding => folded(text, folding))

    val expected = List(
      "{ dependency = "                          -> "",
      ", note = \"Pinned to the sbt version\" }" -> " // Pinned to the sbt version"
    )

    assertEquals(result, expected)
  }

  test("foldings leaves the quoted dependency string uncovered") {
    val text =
      """example = [
        |  { dependency = "com.typesafe:config:=1.4.5", note = "Pinned" }
        |]
        |""".stripMargin

    val result = SbtDependenciesFoldingBuilder.foldings(text)

    val visible = text.substring(result.head.region.end, result.last.region.start)

    val expected = "\"com.typesafe:config:=1.4.5\""

    assertEquals(visible, expected)
  }

  test("foldings tags both regions with the same entry span") {
    val text =
      """example = [
        |  { dependency = "com.typesafe:config:=1.4.5", note = "Pinned" }
        |]
        |""".stripMargin

    val result = SbtDependenciesFoldingBuilder.foldings(text)

    assertEquals(
      result.map(_.entry).distinct.map(span => text.substring(span.start, span.end)),
      List("{ dependency = \"com.typesafe:config:=1.4.5\", note = \"Pinned\" }")
    )
  }

  test("foldings describes a scala-filter annotation") {
    val text =
      """example = [
        |  { dependency = "org.typelevel::cats-core:2.10.0", scala-filter = "2.13" }
        |]
        |""".stripMargin

    val result = SbtDependenciesFoldingBuilder.foldings(text).map(_.placeholder)

    val expected = List("", " // only for Scala 2.13")

    assertEquals(result, expected)
  }

  test("foldings skips multi-line objects, plain entries and objects without annotations") {
    val text =
      """example = [
        |  "org.typelevel::cats-core:2.10.0"
        |  { dependency = "org.scalameta::munit:1.2.4", intransitive = true }
        |  {
        |    dependency = "com.typesafe:config:=1.4.5"
        |    note = "Pinned"
        |  }
        |]
        |""".stripMargin

    val result = SbtDependenciesFoldingBuilder.foldings(text)

    val expected = Nil

    assertEquals(result, expected)
  }

  private def folded(text: String, folding: SbtDependenciesFoldingBuilder.Folding): (String, String) =
    text.substring(folding.region.start, folding.region.end) -> folding.placeholder

}
