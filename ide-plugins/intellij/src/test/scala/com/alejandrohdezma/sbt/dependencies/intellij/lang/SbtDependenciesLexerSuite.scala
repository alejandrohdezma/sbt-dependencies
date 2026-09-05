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

/** Checks the document-level lexer: contiguous coverage, mode-dependent string classification and restartability. */
class SbtDependenciesLexerSuite extends FunSuite {

  test("tokens cover the whole document contiguously") {
    val text =
      """sbt-build = [
        |  "org:name:1.0.0:sbt-plugin" // trailing
        |]
        |
        |/* block
        |   comment */
        |core {
        |  scala-versions = ["2.13.16", "3.3.4"]
        |  dependencies = [
        |    "org.typelevel::cats-core:*"
        |    { dependency = "io.circe::circe-core:{{circe}}", note = "a: note" }
        |  ]
        |}
        |""".stripMargin

    val result = lex(text).map(_._2).mkString

    assertEquals(result, text)
  }

  test("group names, keys and punctuation are classified") {
    val result = lex("""core = ["a:b:1.0.0"]""")

    val expected = List(
      "GROUP_NAME"  -> "core",
      "WHITE_SPACE" -> " ",
      "EQ"          -> "=",
      "WHITE_SPACE" -> " ",
      "LBRACKET"    -> "[",
      "DEP_STRING"  -> "\"a:b:1.0.0\"",
      "RBRACKET"    -> "]"
    )

    assertEquals(result, expected)
  }

  test("strings in a simple group are dependency strings") {
    val result = lex("core = [\n  \"a:b:1.0.0\"\n]").filter(_._1 == "DEP_STRING")

    assertEquals(result, List("DEP_STRING" -> "\"a:b:1.0.0\""))
  }

  test("strings in scala-versions arrays are plain strings") {
    val result = lex("core {\n  scala-versions = [\"2.13.16\"]\n}")

    assertEquals(result.filter(_._1 == "STRING"), List("STRING" -> "\"2.13.16\""))
    assertEquals(result.filter(_._1 == "SETTING_KEY"), List("SETTING_KEY" -> "scala-versions"))
  }

  test("strings in a dependencies array are dependency strings") {
    val result = lex("core {\n  dependencies = [\n    \"a::b:1.0.0\"\n  ]\n}").filter(_._1 == "DEP_STRING")

    assertEquals(result, List("DEP_STRING" -> "\"a::b:1.0.0\""))
  }

  test("object entries distinguish dependency values from notes") {
    val result = lex("""core = [{ dependency = "a:b:1.0.0", note = "why: because" }]""")

    assertEquals(result.filter(_._1 == "DEP_STRING"), List("DEP_STRING" -> "\"a:b:1.0.0\""))
    assertEquals(result.filter(_._1 == "STRING"), List("STRING" -> "\"why: because\""))
    assertEquals(result.filter(_._1 == "OBJECT_KEY").map(_._2), List("dependency", "note"))
  }

  test("intransitive flag lexes as keyword") {
    val result = lex("""core = [{ dependency = "a:b:1.0.0", intransitive = true }]""").filter(_._1 == "KEYWORD")

    assertEquals(result, List("KEYWORD" -> "true"))
  }

  test("overrides flag lexes as object key and keyword") {
    val result = lex("""core = [{ dependency = "a:b:1.0.0:bom", overrides = true }]""")

    assertEquals(result.filter(_._1 == "OBJECT_KEY").map(_._2), List("dependency", "overrides"))
    assertEquals(result.filter(_._1 == "KEYWORD"), List("KEYWORD" -> "true"))
  }

  test("line and block comments") {
    val result = lex("# hash\n// line\n/* block\nstill */\ncore = []").filter(_._1 == "COMMENT").map(_._2)

    assertEquals(result, List("# hash", "// line", "/* block\nstill */"))
  }

  test("restarting from a stored state classifies strings the same way") {
    val text = "core {\n  dependencies = [\n    \"a::b:1.0.0\"\n  ]\n}"

    val lexer = new SbtDependenciesLexer
    lexer.start(text, 0, text.length, 0)

    while (lexer.getTokenType != null && !text.substring(lexer.getTokenStart, lexer.getTokenEnd).startsWith("\""))
      lexer.advance()

    val restartOffset = lexer.getTokenStart
    val restartState  = lexer.getState

    val restarted = new SbtDependenciesLexer
    restarted.start(text, restartOffset, text.length, restartState)

    assertEquals(restarted.getTokenType.toString, "DEP_STRING")
    assertEquals(lex(text).filter(_._1 == "DEP_STRING"), List("DEP_STRING" -> "\"a::b:1.0.0\""))
  }

  private def lex(text: String): List[(String, String)] = {
    val lexer = new SbtDependenciesLexer

    lexer.start(text, 0, text.length, 0)

    val tokens = ListBuffer.empty[(String, String)]

    while (lexer.getTokenType != null) {
      tokens += lexer.getTokenType.toString -> text.substring(lexer.getTokenStart, lexer.getTokenEnd)
      lexer.advance()
    }

    tokens.toList
  }

}
