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

package com.alejandrohdezma.sbt.dependencies.io

import com.alejandrohdezma.sbt.dependencies.constraints.PostUpdateHook
import com.alejandrohdezma.sbt.dependencies.constraints.ScalafixMigration
import com.alejandrohdezma.sbt.dependencies.io.DependencyDiff._

class UpdateScriptSuite extends munit.FunSuite {

  // --- fromHooks ---

  test("fromHooks matches hook with matching groupId and artifactId") {
    val hook = PostUpdateHook(
      groupId = Some("com.github.liancheng"),
      artifactId = Some("organize-imports"),
      command = List("sbt", "scalafixAll"),
      commitMessage = "Reorganize imports with OrganizeImports ${nextVersion}"
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("com.github.liancheng", "organize-imports", "0.5.0", "0.6.0")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromHooks(List(hook), diffs)

    assertEquals(result, List(UpdateScript("sbt scalafixAll", "Reorganize imports with OrganizeImports 0.6.0")))
  }

  test("fromHooks skips hook when groupId does not match") {
    val hook = PostUpdateHook(
      groupId = Some("com.github.liancheng"),
      artifactId = Some("organize-imports"),
      command = List("sbt", "scalafixAll"),
      commitMessage = "Reorganize imports"
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromHooks(List(hook), diffs)

    assertEquals(result, Nil)
  }

  test("fromHooks matches hook with no groupId or artifactId (global hook)") {
    val hook = PostUpdateHook(
      groupId = None,
      artifactId = None,
      command = List("sbt", "compile"),
      commitMessage = "Recompile after updating ${artifactName}"
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromHooks(List(hook), diffs)

    assertEquals(result, List(UpdateScript("sbt compile", "Recompile after updating cats-core_2.13")))
  }

  test("fromHooks substitutes all variables in commit message") {
    val hook = PostUpdateHook(
      groupId = Some("org.typelevel"),
      artifactId = None,
      command = List("sbt", "test"),
      commitMessage = "Update ${artifactName} from ${currentVersion} to ${nextVersion}"
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromHooks(List(hook), diffs)

    assertEquals(result, List(UpdateScript("sbt test", "Update cats-core_2.13 from 2.9.0 to 2.10.0")))
  }

  test("fromHooks deduplicates by script") {
    val hook = PostUpdateHook(
      groupId = Some("org.typelevel"),
      artifactId = None,
      command = List("sbt", "scalafixAll"),
      commitMessage = "Run scalafix for ${artifactName}"
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(
          UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0"),
          UpdatedDep("org.typelevel", "cats-kernel_2.13", "2.9.0", "2.10.0")
        ),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromHooks(List(hook), diffs)

    // Both match but produce same script, so deduplicated to one
    assertEquals(result.size, 1)
    assertEquals(result.head.script, "sbt scalafixAll")
  }

  // --- fromMigrations ---

  test("fromMigrations matches migration within version range") {
    val migration = ScalafixMigration(
      groupId = "co.fs2",
      artifactIds = List("fs2-.*"),
      newVersion = "3.0.7",
      rewriteRules = List("replace:fs2.text.utf8Decode/fs2.text.utf8.decode")
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("co.fs2", "fs2-core_2.13", "2.5.0", "3.1.0")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromMigrations(List(migration), diffs)

    assertEquals(result.size, 1)
    assertEquals(
      result.head.script,
      """sbt "scalafixEnable; core/scalafixAll replace:fs2.text.utf8Decode/fs2.text.utf8.decode""""
    )
    assert(result.head.message.contains("core"))
  }

  test("fromMigrations skips migration when from >= newVersion") {
    val migration = ScalafixMigration(
      groupId = "co.fs2",
      artifactIds = List("fs2-.*"),
      newVersion = "3.0.7",
      rewriteRules = List("some-rule")
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("co.fs2", "fs2-core_2.13", "3.0.7", "3.1.0")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromMigrations(List(migration), diffs)

    assertEquals(result, Nil)
  }

  test("fromMigrations skips migration when to < newVersion") {
    val migration = ScalafixMigration(
      groupId = "co.fs2",
      artifactIds = List("fs2-.*"),
      newVersion = "3.0.7",
      rewriteRules = List("some-rule")
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("co.fs2", "fs2-core_2.13", "2.5.0", "3.0.6")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromMigrations(List(migration), diffs)

    assertEquals(result, Nil)
  }

  test("fromMigrations uses regex for artifactId matching") {
    val migration = ScalafixMigration(
      groupId = "co.fs2",
      artifactIds = List("fs2-.*"),
      newVersion = "1.0.0",
      rewriteRules = List("rule1")
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(
          UpdatedDep("co.fs2", "fs2-core_2.13", "0.9.0", "1.0.0"),
          UpdatedDep("co.fs2", "fs2-io_2.13", "0.9.0", "1.0.0")
        ),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromMigrations(List(migration), diffs)

    // Both artifacts match the regex, but should produce one script (deduped)
    assertEquals(result.size, 1)
    assert(result.head.script.contains("core/scalafixAll"))
  }

  test("fromMigrations scopes scalafixAll to the project") {
    val migration = ScalafixMigration(
      groupId = "org.typelevel",
      artifactIds = List("cats-core.*"),
      newVersion = "2.10.0",
      rewriteRules = List("CatsRule")
    )

    val diffs = Map(
      "myproject" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromMigrations(List(migration), diffs)

    assertEquals(result.size, 1)
    assertEquals(result.head.script, """sbt "scalafixEnable; myproject/scalafixAll CatsRule"""")
  }

  test("fromMigrations generates separate scripts for different projects") {
    val migration = ScalafixMigration(
      groupId = "org.typelevel",
      artifactIds = List("cats-core.*"),
      newVersion = "2.10.0",
      rewriteRules = List("CatsRule")
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = Nil,
        removed = Nil
      ),
      "web" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromMigrations(List(migration), diffs)

    assertEquals(result.size, 2)
    assert(result.exists(_.script.contains("core/scalafixAll")))
    assert(result.exists(_.script.contains("web/scalafixAll")))
  }

  test("fromMigrations uses scalafix CLI for sbt-build project") {
    val migration = ScalafixMigration(
      groupId = "ch.epfl.scala",
      artifactIds = List("sbt-scalafix"),
      newVersion = "0.9.21",
      rewriteRules = List("Sbt0_13BuildSyntax")
    )

    val diffs = Map(
      "sbt-build" -> ProjectDiff(
        updated = List(UpdatedDep("ch.epfl.scala", "sbt-scalafix", "0.9.0", "0.9.21")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromMigrations(List(migration), diffs)

    assertEquals(result.size, 1)
    assertEquals(result.head.script, "scalafix --rules=Sbt0_13BuildSyntax")
    assert(result.head.message.contains("build"))
  }

  test("fromMigrations includes scalacOptions in sbt command") {
    val migration = ScalafixMigration(
      groupId = "org.typelevel", artifactIds = List("cats-core.*"), newVersion = "2.2.0",
      rewriteRules = List("CatsRule"), scalacOptions = List("-P:semanticdb:synthetics:on")
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.1.0", "2.2.0")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromMigrations(List(migration), diffs)

    assertEquals(result.size, 1)
    assertEquals(
      result.head.script,
      """sbt "scalafixEnable; set ThisBuild / scalacOptions ++= List("-P:semanticdb:synthetics:on"); core/scalafixAll CatsRule""""
    )
  }

  test("fromMigrations appends doc URL to message") {
    val migration = ScalafixMigration(
      groupId = "org.typelevel", artifactIds = List("cats-core.*"), newVersion = "2.2.0",
      rewriteRules = List("CatsRule"), doc = Some("https://github.com/typelevel/cats/blob/v2.2.0/scalafix/README.md")
    )

    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.1.0", "2.2.0")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromMigrations(List(migration), diffs)

    assertEquals(result.size, 1)
    assertEquals(
      result.head.message,
      "Run scalafix migration in core: CatsRule (see https://github.com/typelevel/cats/blob/v2.2.0/scalafix/README.md)"
    )
  }

  test("fromMigrations ignores scalacOptions for sbt-build project") {
    val migration = ScalafixMigration(
      groupId = "ch.epfl.scala", artifactIds = List("sbt-scalafix"), newVersion = "0.9.21",
      rewriteRules = List("BuildRule"), scalacOptions = List("-P:semanticdb:synthetics:on")
    )

    val diffs = Map(
      "sbt-build" -> ProjectDiff(
        updated = List(UpdatedDep("ch.epfl.scala", "sbt-scalafix", "0.9.0", "0.9.21")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromMigrations(List(migration), diffs)

    assertEquals(result.size, 1)
    // scalafix CLI doesn't use scalacOptions
    assertEquals(result.head.script, "scalafix --rules=BuildRule")
  }

  test("fromMigrations generates scalafix CLI script with multiple rules for sbt-build") {
    val migration = ScalafixMigration(
      groupId = "ch.epfl.scala",
      artifactIds = List("sbt-scalafix"),
      newVersion = "0.9.21",
      rewriteRules = List("Rule1", "Rule2")
    )

    val diffs = Map(
      "sbt-build" -> ProjectDiff(
        updated = List(UpdatedDep("ch.epfl.scala", "sbt-scalafix", "0.9.0", "0.9.21")),
        added = Nil,
        removed = Nil
      )
    )

    val result = UpdateScript.fromMigrations(List(migration), diffs)

    assertEquals(result.size, 1)
    assertEquals(result.head.script, "scalafix --rules=Rule1 --rules=Rule2")
    assert(result.head.message.contains("build"))
  }

  // --- toJson ---

  test("toJson renders empty list") {
    assertEquals(UpdateScript.toJson(Nil), "[]")
  }

  test("toJson renders scripts as JSON array") {
    val scripts = List(
      UpdateScript("sbt scalafixAll", "Reorganize imports"),
      UpdateScript("sbt headerCreateAll", "Update headers")
    )

    val result = UpdateScript.toJson(scripts)

    val expected =
      """|[
         |  {"script": "sbt scalafixAll", "message": "Reorganize imports"},
         |  {"script": "sbt headerCreateAll", "message": "Update headers"}
         |]""".stripMargin

    assertNoDiff(result, expected)
  }

  test("toJson escapes quotes in scripts") {
    val scripts = List(
      UpdateScript("""sbt "scalafixEnable; core/scalafixAll Rule1"""", "Run migration")
    )

    val result = UpdateScript.toJson(scripts)

    assert(result.contains("""\""""))
  }

}
