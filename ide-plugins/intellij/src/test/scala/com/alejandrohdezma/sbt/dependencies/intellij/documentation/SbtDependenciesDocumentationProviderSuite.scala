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

package com.alejandrohdezma.sbt.dependencies.intellij.documentation

class SbtDependenciesDocumentationProviderSuite extends munit.FunSuite {

  test("hoverHtml describes a full dependency") {
    val text =
      """example = [
        |  "org.typelevel::cats-core:^2.10.0:test"
        |]
        |""".stripMargin

    val result = SbtDependenciesDocumentationProvider.hoverHtml(text, text.indexOf("cats-core"))

    val expected = Some(
      "<div class='definition'><pre><b>org.typelevel</b> <code>::</code> <b>cats-core</b></pre></div>" +
        "<div class='content'>Version: <code>^2.10.0</code> <i>(update within major)</i>" +
        "<br/>Configuration: <code>test</code>" +
        "<p><a href=\"https://mvnrepository.com/artifact/org.typelevel/cats-core\">Open on mvnrepository</a></div>"
    )

    assertEquals(result, expected)
  }

  test("hoverHtml describes a versionless dependency") {
    val text =
      """example = [
        |  "org.typelevel::cats-core"
        |]
        |""".stripMargin

    val result = SbtDependenciesDocumentationProvider.hoverHtml(text, text.indexOf("org.typelevel"))

    assertEquals(result.exists(_.contains("Version: <i>resolved to latest</i>")), true)
  }

  test("hoverHtml explains each version marker") {
    val versions = List(
      "*"         -> "managed by BOM",
      "{{catsV}}" -> "resolved from variable",
      "=2.10.0"   -> "pinned",
      "~2.10.0"   -> "update within minor",
      "2.10.0"    -> "update to latest"
    )

    versions.foreach { case (version, explanation) =>
      val text =
        s"""example = [
           |  "org.typelevel::cats-core:$version"
           |]
           |""".stripMargin

      val result = SbtDependenciesDocumentationProvider.hoverHtml(text, text.indexOf("cats-core"))

      assertEquals(result.exists(_.contains(s"<i>($explanation)</i>")), true, version)
    }
  }

  test("hoverHtml works on the dependency field of an object entry") {
    val text =
      """example = [
        |  { dependency = "com.typesafe:config:=1.4.5", note = "Pinned" }
        |]
        |""".stripMargin

    val result = SbtDependenciesDocumentationProvider.hoverHtml(text, text.indexOf("config"))

    assertEquals(result.exists(_.contains("<b>config</b>")), true)
  }

  test("hoverHtml returns None outside any entry") {
    val text =
      """example = [
        |  "org.typelevel::cats-core:2.10.0"
        |]
        |""".stripMargin

    val result = SbtDependenciesDocumentationProvider.hoverHtml(text, text.indexOf("example"))

    val expected = None

    assertEquals(result, expected)
  }

  test("hoverHtml documents reserved group names") {
    val text =
      """sbt-build = [
        |  "ch.epfl.scala:sbt-scalafix:0.14.7:sbt-plugin"
        |]
        |
        |common-settings {
        |  scala-version = "3.3.7"
        |}
        |""".stripMargin

    val sbtBuild = SbtDependenciesDocumentationProvider.hoverHtml(text, text.indexOf("sbt-build"))

    val commonSettings = SbtDependenciesDocumentationProvider.hoverHtml(text, text.indexOf("common-settings"))

    assertEquals(sbtBuild.exists(_.contains("meta-build dependencies")), true)
    assertEquals(commonSettings.exists(_.contains("build-wide defaults")), true)
  }

  test("hoverHtml returns None on a regular group name") {
    val text =
      """example = [
        |  "org.typelevel::cats-core:2.10.0"
        |]
        |""".stripMargin

    val result = SbtDependenciesDocumentationProvider.hoverHtml(text, text.indexOf("example") + 2)

    val expected = None

    assertEquals(result, expected)
  }

  test("hoverHtml returns None for a dependency that doesn't match the pattern") {
    val text =
      """example = [
        |  "not a dependency"
        |]
        |""".stripMargin

    val result = SbtDependenciesDocumentationProvider.hoverHtml(text, text.indexOf("not"))

    val expected = None

    assertEquals(result, expected)
  }

  test("mvnRepositoryUrl appends the sbt cross suffix to sbt plugins") {
    val result = SbtDependenciesDocumentationProvider.mvnRepositoryUrl("com.example", "sbt-thing", Some("sbt-plugin"))

    val expected = "https://mvnrepository.com/artifact/com.example/sbt-thing_2.12_1.0"

    assertEquals(result, expected)
  }

}
