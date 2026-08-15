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

package com.alejandrohdezma.sbt.dependencies.intellij.lang

import scala.collection.mutable.ListBuffer

import munit.FunSuite

/** Checks the dependency-string fragment lexer: per-part tokens and full fragment coverage. */
class DependencyStringLexerSuite extends FunSuite {

  test("full dependency with version and configuration") {
    val result = lex("\"org.scalameta::munit:1.2.4:test\"")

    val expected = List(
      "QUOTE"         -> "\"",
      "ORGANIZATION"  -> "org.scalameta",
      "SEPARATOR"     -> "::",
      "ARTIFACT"      -> "munit",
      "COLON"         -> ":",
      "VERSION"       -> "1.2.4",
      "COLON"         -> ":",
      "CONFIGURATION" -> "test",
      "QUOTE"         -> "\""
    )

    assertEquals(result, expected)
  }

  test("version markers") {
    assertEquals(lex("\"a:b:=1.0.0\"").filter(_._1 == "VERSION_MARKER"), List("VERSION_MARKER" -> "="))
    assertEquals(lex("\"a:b:^1.0.0\"").filter(_._1 == "VERSION_MARKER"), List("VERSION_MARKER" -> "^"))
    assertEquals(lex("\"a:b:~1.0.0\"").filter(_._1 == "VERSION_MARKER"), List("VERSION_MARKER" -> "~"))
    assertEquals(lex("\"a:b:~1.0.0\"").filter(_._1 == "VERSION"), List("VERSION" -> "1.0.0"))
  }

  test("BOM version") {
    val result = lex("\"org.typelevel::cats-core:*\"").filter(_._1 == "BOM_STAR")

    assertEquals(result, List("BOM_STAR" -> "*"))
  }

  test("variable version") {
    val result = lex("\"io.circe::circe-core:{{circe}}\"").filter(_._1 == "VARIABLE")

    assertEquals(result, List("VARIABLE" -> "{{circe}}"))
  }

  test("dependency without version") {
    val result = lex("\"org:name\"")

    val expected = List(
      "QUOTE"        -> "\"",
      "ORGANIZATION" -> "org",
      "SEPARATOR"    -> ":",
      "ARTIFACT"     -> "name",
      "QUOTE"        -> "\""
    )

    assertEquals(result, expected)
  }

  test("non-dependency content is a single string token") {
    val result = lex("\"just some note\"")

    val expected = List(
      "QUOTE"  -> "\"",
      "STRING" -> "just some note",
      "QUOTE"  -> "\""
    )

    assertEquals(result, expected)
  }

  test("tokens always cover the whole fragment") {
    val fragments = List(
      "\"org.scalameta::munit:1.2.4:test\"", "\"a:b:=1.0.0\"", "\"org.typelevel::cats-core:*\"",
      "\"io.circe::circe-core:{{circe}}\"", "\"not a dependency\"", "\"\"", "\"unterminated"
    )

    fragments.foreach { fragment =>
      val result = lex(fragment).map(_._2).mkString

      assertEquals(result, fragment, s"coverage failed for $fragment")
    }
  }

  private def lex(text: String): List[(String, String)] = {
    val lexer = new DependencyStringLexer

    lexer.start(text, 0, text.length, 0)

    val tokens = ListBuffer.empty[(String, String)]

    while (lexer.getTokenType != null) {
      tokens += lexer.getTokenType.toString -> text.substring(lexer.getTokenStart, lexer.getTokenEnd)
      lexer.advance()
    }

    tokens.toList
  }

}
