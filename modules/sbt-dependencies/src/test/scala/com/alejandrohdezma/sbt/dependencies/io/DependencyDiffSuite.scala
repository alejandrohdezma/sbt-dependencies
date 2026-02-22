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

import java.io.File
import java.nio.file.Files

import sbt.librarymanagement.ModuleID

import com.alejandrohdezma.sbt.dependencies.io.DependencyDiff._

class DependencyDiffSuite extends munit.FunSuite {

  // --- ResolvedDep.fromModuleID ---

  test("ResolvedDep.fromModuleID extracts org, name, revision") {
    val moduleID = ModuleID("org.typelevel", "cats-core_2.13", "2.10.0")
    val result   = ResolvedDep.fromModuleID(moduleID)
    assertEquals(result, ResolvedDep("org.typelevel", "cats-core_2.13", "2.10.0"))
  }

  // --- writeSnapshot / readSnapshot round-trip ---

  fileFixture.test("writeSnapshot / readSnapshot round-trip preserves data") { file =>
    val snapshot = Map(
      "core" -> Set(
        ResolvedDep("org.typelevel", "cats-core_2.13", "2.9.0"),
        ResolvedDep("org.typelevel", "cats-kernel_2.13", "2.9.0")
      ),
      "web" -> Set(
        ResolvedDep("org.http4s", "http4s-core_2.13", "0.23.0")
      )
    )

    writeSnapshot(file, snapshot)

    val content = sbt.io.IO.read(file)

    val expected =
      """|core=[
         |    {
         |        name="cats-core_2.13"
         |        organization="org.typelevel"
         |        revision="2.9.0"
         |    },
         |    {
         |        name="cats-kernel_2.13"
         |        organization="org.typelevel"
         |        revision="2.9.0"
         |    }
         |]
         |web=[
         |    {
         |        name="http4s-core_2.13"
         |        organization="org.http4s"
         |        revision="0.23.0"
         |    }
         |]
         |""".stripMargin

    assertNoDiff(content, expected)

    val result = readSnapshot(file)

    assertEquals(result, snapshot)
  }

  fileFixture.test("writeSnapshot / readSnapshot round-trip with empty snapshot") { file =>
    val snapshot = Map.empty[String, Set[ResolvedDep]]

    writeSnapshot(file, snapshot)
    val result = readSnapshot(file)

    assertEquals(result, snapshot)
  }

  fileFixture.test("writeSnapshot / readSnapshot round-trip with single project") { file =>
    val snapshot = Map(
      "myproject" -> Set(ResolvedDep("com.example", "lib_2.13", "1.0.0"))
    )

    writeSnapshot(file, snapshot)
    val result = readSnapshot(file)

    assertEquals(result, snapshot)
  }

  // --- compute ---

  test("compute detects updated dependencies") {
    val before = Map("core" -> Set(ResolvedDep("org.typelevel", "cats-core_2.13", "2.9.0")))
    val after  = Map("core" -> Set(ResolvedDep("org.typelevel", "cats-core_2.13", "2.10.0")))

    val result = compute(before, after)

    val expected = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = Nil,
        removed = Nil
      )
    )

    assertEquals(result, expected)
  }

  test("compute detects added dependencies") {
    val before = Map("core" -> Set.empty[ResolvedDep])
    val after  = Map("core" -> Set(ResolvedDep("org.typelevel", "cats-core_2.13", "2.10.0")))

    val result = compute(before, after)

    val expected = Map(
      "core" -> ProjectDiff(
        updated = Nil,
        added = List(ResolvedDep("org.typelevel", "cats-core_2.13", "2.10.0")),
        removed = Nil
      )
    )

    assertEquals(result, expected)
  }

  test("compute detects removed dependencies") {
    val before = Map("core" -> Set(ResolvedDep("org.typelevel", "cats-core_2.13", "2.9.0")))
    val after  = Map("core" -> Set.empty[ResolvedDep])

    val result = compute(before, after)

    val expected = Map(
      "core" -> ProjectDiff(
        updated = Nil,
        added = Nil,
        removed = List(ResolvedDep("org.typelevel", "cats-core_2.13", "2.9.0"))
      )
    )

    assertEquals(result, expected)
  }

  test("compute handles mixed changes") {
    val before = Map(
      "core" -> Set(
        ResolvedDep("org.typelevel", "cats-core_2.13", "2.9.0"),
        ResolvedDep("org.typelevel", "cats-macros_2.13", "2.9.0")
      )
    )
    val after = Map(
      "core" -> Set(
        ResolvedDep("org.typelevel", "cats-core_2.13", "2.10.0"),
        ResolvedDep("org.typelevel", "cats-parse_2.13", "1.1.0")
      )
    )

    val result = compute(before, after)

    val expected = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = List(ResolvedDep("org.typelevel", "cats-parse_2.13", "1.1.0")),
        removed = List(ResolvedDep("org.typelevel", "cats-macros_2.13", "2.9.0"))
      )
    )

    assertEquals(result, expected)
  }

  test("compute returns empty map when no changes") {
    val snapshot = Map("core" -> Set(ResolvedDep("org.typelevel", "cats-core_2.13", "2.10.0")))

    val result = compute(snapshot, snapshot)

    assertEquals(result, Map.empty[String, ProjectDiff])
  }

  test("compute handles multiple projects") {
    val before = Map(
      "core" -> Set(ResolvedDep("org.typelevel", "cats-core_2.13", "2.9.0")),
      "web"  -> Set(ResolvedDep("org.http4s", "http4s-core_2.13", "0.23.0"))
    )
    val after = Map(
      "core" -> Set(ResolvedDep("org.typelevel", "cats-core_2.13", "2.10.0")),
      "web"  -> Set(ResolvedDep("org.http4s", "http4s-core_2.13", "0.23.0"))
    )

    val result = compute(before, after)

    val expected = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = Nil,
        removed = Nil
      )
    )

    assertEquals(result, expected)
  }

  test("compute handles project only in after") {
    val before = Map.empty[String, Set[ResolvedDep]]
    val after  = Map("core" -> Set(ResolvedDep("org.typelevel", "cats-core_2.13", "2.10.0")))

    val result = compute(before, after)

    val expected = Map(
      "core" -> ProjectDiff(
        updated = Nil,
        added = List(ResolvedDep("org.typelevel", "cats-core_2.13", "2.10.0")),
        removed = Nil
      )
    )

    assertEquals(result, expected)
  }

  test("compute handles project only in before") {
    val before = Map("core" -> Set(ResolvedDep("org.typelevel", "cats-core_2.13", "2.9.0")))
    val after  = Map.empty[String, Set[ResolvedDep]]

    val result = compute(before, after)

    val expected = Map(
      "core" -> ProjectDiff(
        updated = Nil,
        added = Nil,
        removed = List(ResolvedDep("org.typelevel", "cats-core_2.13", "2.9.0"))
      )
    )

    assertEquals(result, expected)
  }

  // --- toHocon ---

  test("toHocon renders all change types") {
    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = List(ResolvedDep("org.typelevel", "cats-parse_2.13", "1.1.0")),
        removed = List(ResolvedDep("org.typelevel", "cats-macros_2.13", "2.9.0"))
      )
    )

    val result = toHocon(diffs)

    val expected =
      """|core {
         |    added=[
         |        {
         |            name="cats-parse_2.13"
         |            organization="org.typelevel"
         |            version="1.1.0"
         |        }
         |    ]
         |    removed=[
         |        {
         |            name="cats-macros_2.13"
         |            organization="org.typelevel"
         |            version="2.9.0"
         |        }
         |    ]
         |    updated=[
         |        {
         |            from="2.9.0"
         |            name="cats-core_2.13"
         |            organization="org.typelevel"
         |            to="2.10.0"
         |        }
         |    ]
         |}
         |""".stripMargin

    assertNoDiff(result, expected)
  }

  test("toHocon renders multiple projects") {
    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = Nil,
        removed = Nil
      ),
      "web" -> ProjectDiff(
        updated = Nil,
        added = List(ResolvedDep("org.http4s", "http4s-core_2.13", "0.23.1")),
        removed = Nil
      )
    )

    val result = toHocon(diffs)

    val expected =
      """|core {
         |    added=[]
         |    removed=[]
         |    updated=[
         |        {
         |            from="2.9.0"
         |            name="cats-core_2.13"
         |            organization="org.typelevel"
         |            to="2.10.0"
         |        }
         |    ]
         |}
         |web {
         |    added=[
         |        {
         |            name="http4s-core_2.13"
         |            organization="org.http4s"
         |            version="0.23.1"
         |        }
         |    ]
         |    removed=[]
         |    updated=[]
         |}
         |""".stripMargin

    assertNoDiff(result, expected)
  }

  test("toHocon renders empty lists within a project diff") {
    val diffs = Map(
      "core" -> ProjectDiff(
        updated = List(UpdatedDep("org.typelevel", "cats-core_2.13", "2.9.0", "2.10.0")),
        added = Nil,
        removed = Nil
      )
    )

    val result = toHocon(diffs)

    val expected =
      """|core {
         |    added=[]
         |    removed=[]
         |    updated=[
         |        {
         |            from="2.9.0"
         |            name="cats-core_2.13"
         |            organization="org.typelevel"
         |            to="2.10.0"
         |        }
         |    ]
         |}
         |""".stripMargin

    assertNoDiff(result, expected)
  }

  // --- compute with plugin snapshot merging ---

  test("compute detects plugin version update when merged into sbt-build group") {
    val buildDeps   = Set(ResolvedDep("org.typelevel", "cats-core_2.13", "2.9.0"))
    val pluginDep   = ResolvedDep("com.alejandrohdezma", "sbt-dependencies", "1.0.0")
    val updatedPlug = ResolvedDep("com.alejandrohdezma", "sbt-dependencies", "2.0.0")

    val before = Map("sbt-build" -> (buildDeps + pluginDep))
    val after  = Map("sbt-build" -> (buildDeps + updatedPlug))

    val result = compute(before, after)

    val expected = Map(
      "sbt-build" -> ProjectDiff(
        updated = List(UpdatedDep("com.alejandrohdezma", "sbt-dependencies", "1.0.0", "2.0.0")),
        added = Nil,
        removed = Nil
      )
    )

    assertEquals(result, expected)
  }

  test("compute returns empty diff when plugin version is unchanged") {
    val deps = Set(
      ResolvedDep("com.alejandrohdezma", "sbt-dependencies", "1.0.0"),
      ResolvedDep("org.typelevel", "cats-core_2.13", "2.9.0")
    )
    val before = Map("sbt-build" -> deps)
    val after  = Map("sbt-build" -> deps)

    val result = compute(before, after)

    assertEquals(result, Map.empty[String, ProjectDiff])
  }

  test("compute detects sbt version update when merged into sbt-build group") {
    val buildDeps  = Set(ResolvedDep("org.typelevel", "cats-core_2.13", "2.9.0"))
    val sbtDep     = ResolvedDep("org.scala-sbt", "sbt", "1.9.0")
    val updatedSbt = ResolvedDep("org.scala-sbt", "sbt", "1.10.0")

    val before = Map("sbt-build" -> (buildDeps + sbtDep))
    val after  = Map("sbt-build" -> (buildDeps + updatedSbt))

    val result = compute(before, after)

    val expected = Map(
      "sbt-build" -> ProjectDiff(
        updated = List(UpdatedDep("org.scala-sbt", "sbt", "1.9.0", "1.10.0")),
        added = Nil,
        removed = Nil
      )
    )

    assertEquals(result, expected)
  }

  test("compute detects plugin as added when only in after") {
    val before = Map("sbt-build" -> Set.empty[ResolvedDep])
    val after  = Map("sbt-build" -> Set(ResolvedDep("com.alejandrohdezma", "sbt-dependencies", "1.0.0")))

    val result = compute(before, after)

    val expected = Map(
      "sbt-build" -> ProjectDiff(
        updated = Nil,
        added = List(ResolvedDep("com.alejandrohdezma", "sbt-dependencies", "1.0.0")),
        removed = Nil
      )
    )

    assertEquals(result, expected)
  }

  def fileFixture: FunFixture[File] = FunFixture[File](
    setup = { _ =>
      val file = Files.createTempFile("snapshot", ".txt").toFile
      file
    },
    teardown = { file =>
      Files.deleteIfExists(file.toPath)
      ()
    }
  )

}
