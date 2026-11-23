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

package com.alejandrohdezma.sbt.dependencies.intellij.formatting

import java.io.File
import java.util.Collections

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.openapi.util.TextRange
import munit.FunSuite

/** Checks the formatting service mirrors the SBT plugin's canonical output: sorting, indentation, comment stripping.
  * The service is driven through a stubbed [[AsyncFormattingRequest]], no IDE runtime involved.
  */
class SbtDependenciesFormattingServiceSuite extends FunSuite {

  test("sorts groups (sbt-build, common-settings, then alphabetical) and dependencies") {
    val input =
      """zebra = [
        |  "org.scalameta::munit:1.2.4:test"
        |  "io.circe::circe-core:{{circe}}"
        |]
        |
        |common-settings {
        |  scala-version = "3.8.4"
        |}
        |
        |sbt-build = [
        |  "com.alejandrohdezma:sbt-ci:5.0.0:sbt-plugin"
        |]
        |""".stripMargin

    val result = format(input)

    val expected =
      """sbt-build = [
        |  "com.alejandrohdezma:sbt-ci:5.0.0:sbt-plugin"
        |]
        |
        |common-settings {
        |  scala-version = "3.8.4"
        |}
        |
        |zebra = [
        |  "io.circe::circe-core:{{circe}}"
        |  "org.scalameta::munit:1.2.4:test"
        |]
        |""".stripMargin

    assertEquals(result, expected)
  }

  test("strips comments and normalizes indentation") {
    val input =
      """// header comment
        |core = [
        |      "a:b:1.0.0" # trailing
        |]
        |""".stripMargin

    val result = format(input)

    val expected =
      """core = [
        |  "a:b:1.0.0"
        |]
        |""".stripMargin

    assertEquals(result, expected)
  }

  test("keeps annotated entries as single-line objects when they fit") {
    val input =
      """core = [
        |  {
        |    dependency = "a:b:=1.0.0"
        |    note = "pinned"
        |  }
        |]
        |""".stripMargin

    val result = format(input)

    val expected =
      """core = [
        |  { dependency = "a:b:=1.0.0", note = "pinned" }
        |]
        |""".stripMargin

    assertEquals(result, expected)
  }

  test("advanced groups keep java-version, scala-versions and dependencies sections") {
    val input =
      """core {
        |  dependencies = [
        |    "a:b:1.0.0"
        |  ]
        |  scala-versions = ["2.13.16", "3.3.4"]
        |  java-version = "21"
        |}
        |""".stripMargin

    val result = format(input)

    val expected =
      """core {
        |  java-version = "21"
        |  scala-versions = ["2.13.16", "3.3.4"]
        |  dependencies = [
        |    "a:b:1.0.0"
        |  ]
        |}
        |""".stripMargin

    assertEquals(result, expected)
  }

  test("formatting is idempotent") {
    val input =
      """sbt-build = [
        |  "com.alejandrohdezma:sbt-ci:5.0.0:sbt-plugin"
        |]
        |
        |core = [
        |  "a:b:1.0.0"
        |  { dependency = "c::d:^2.0.0", note = "pinned" }
        |]
        |""".stripMargin

    val once = format(input)

    val result = format(once)

    assertEquals(result, once)
  }

  test("invalid HOCON is left untouched") {
    val result = format("core = [ \"unclosed")

    assertEquals(result, "core = [ \"unclosed")
  }

  test("invalid group shapes are left untouched") {
    val result = format("core = \"not a list\"")

    assertEquals(result, "core = \"not a list\"")
  }

  private def format(text: String): String = {
    var result = Option.empty[String]

    val request: AsyncFormattingRequest = new AsyncFormattingRequest {

      override def getDocumentText: String = text

      override def onTextReady(updatedText: String): Unit = result = Some(updatedText)

      override def onError(title: String, message: String): Unit = fail(s"$title: $message")

      override def onError(title: String, message: String, offset: Int): Unit = fail(s"$title: $message")

      override def getIOFile: File = null

      override def getFormattingRanges: java.util.List[TextRange] = Collections.emptyList()

      override def canChangeWhitespaceOnly: Boolean = false

      override def isQuickFormat: Boolean = false

      override def getContext: FormattingContext = null

    }

    new SbtDependenciesFormattingService().createFormattingTask(request).run()

    result.getOrElse(fail("onTextReady was never called"))
  }

}
