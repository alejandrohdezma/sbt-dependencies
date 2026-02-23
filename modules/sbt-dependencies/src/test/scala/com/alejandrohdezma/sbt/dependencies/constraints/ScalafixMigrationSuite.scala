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

package com.alejandrohdezma.sbt.dependencies.constraints

import java.io.File
import java.net.URL
import java.nio.file.Files

import sbt.IO

import com.alejandrohdezma.sbt.dependencies.TestLogger

class ScalafixMigrationSuite extends munit.FunSuite {

  implicit val logger: TestLogger = TestLogger()

  private val tempCacheDir = Files.createTempDirectory("config-cache")

  implicit val configCache: ConfigCache = ConfigCache(tempCacheDir.toFile())

  override def afterAll(): Unit = IO.delete(tempCacheDir.toFile())

  override def beforeEach(context: BeforeEach): Unit = logger.cleanLogs()

  withMigrationFile {
    """migrations = [
      |  {
      |    groupId: "co.fs2"
      |    artifactIds: ["fs2-.*"]
      |    newVersion: "1.0.0"
      |    rewriteRules: ["github:functional-streams-for-scala/fs2/v1?sha=v1.0.5"]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with all fields") { urls =>
    val migrations = ScalafixMigration.loadFromUrls(urls)

    val expected = ScalafixMigration(
      groupId = "co.fs2", artifactIds = List("fs2-.*"), newVersion = "1.0.0",
      rewriteRules = List("github:functional-streams-for-scala/fs2/v1?sha=v1.0.5")
    )

    assertEquals(migrations, List(expected))
  }

  withMigrationFile {
    """migrations = [
      |  {
      |    groupId: "co.fs2"
      |    artifactIds: ["fs2-core", "fs2-io", "fs2-reactive-streams"]
      |    newVersion: "3.0.7"
      |    rewriteRules: ["rule1", "rule2"]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls handles multiple artifactIds and rewriteRules") { urls =>
    val migrations = ScalafixMigration.loadFromUrls(urls)

    val expected = List(
      ScalafixMigration(
        groupId = "co.fs2", artifactIds = List("fs2-core", "fs2-io", "fs2-reactive-streams"),
        newVersion = "3.0.7", rewriteRules = List("rule1", "rule2")
      )
    )

    assertEquals(migrations, expected)
  }

  withMigrationFile {
    """migrations = [
      |  {
      |    groupId: "co.fs2"
      |    artifactIds: ["fs2-.*"]
      |    newVersion: "1.0.0"
      |    rewriteRules: ["github:functional-streams-for-scala/fs2/v1?sha=v1.0.5"]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls defaults doc to None and scalacOptions to Nil") { urls =>
    val migrations = ScalafixMigration.loadFromUrls(urls)

    val expected = List(
      ScalafixMigration(
        groupId = "co.fs2", artifactIds = List("fs2-.*"), newVersion = "1.0.0",
        rewriteRules = List("github:functional-streams-for-scala/fs2/v1?sha=v1.0.5")
      )
    )

    assertEquals(migrations, expected)
  }

  withMigrationFile {
    """migrations = [
      |  {
      |    groupId: "org.typelevel"
      |    artifactIds: ["cats-core"]
      |    newVersion: "2.2.0"
      |    rewriteRules: ["github:typelevel/cats/Cats_v2_2_0?sha=v2.2.0"]
      |    doc: "https://github.com/typelevel/cats/blob/v2.2.0/scalafix/README.md"
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses doc field") { urls =>
    val migrations = ScalafixMigration.loadFromUrls(urls)

    val expected = List(
      ScalafixMigration(
        groupId = "org.typelevel", artifactIds = List("cats-core"), newVersion = "2.2.0",
        rewriteRules = List("github:typelevel/cats/Cats_v2_2_0?sha=v2.2.0"),
        doc = Some("https://github.com/typelevel/cats/blob/v2.2.0/scalafix/README.md")
      )
    )

    assertEquals(migrations, expected)
  }

  withMigrationFile {
    """migrations = [
      |  {
      |    groupId: "org.typelevel"
      |    artifactIds: ["cats-core"]
      |    newVersion: "2.2.0"
      |    rewriteRules: ["github:typelevel/cats/Cats_v2_2_0?sha=v2.2.0"]
      |    scalacOptions: ["-P:semanticdb:synthetics:on"]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses scalacOptions field") { urls =>
    val migrations = ScalafixMigration.loadFromUrls(urls)

    val expected = List(
      ScalafixMigration(
        groupId = "org.typelevel", artifactIds = List("cats-core"), newVersion = "2.2.0",
        rewriteRules = List("github:typelevel/cats/Cats_v2_2_0?sha=v2.2.0"),
        scalacOptions = List("-P:semanticdb:synthetics:on")
      )
    )

    assertEquals(migrations, expected)
  }

  withMigrationFile {
    """postUpdateHooks = [
      |  { command = ["sbt", "test"], commitMessage = "test" }
      |]
      |""".stripMargin
  }.test("loadFromUrls skips files without migrations path") { urls =>
    val migrations = ScalafixMigration.loadFromUrls(urls)

    assertEquals(migrations, Nil)
  }

  def withMigrationFile(contents: String*) = FunFixture[List[URL]](
    setup = { _ =>
      contents.toList.map { content =>
        val file = Files.createTempFile("migrations", ".conf")
        IO.write(file.toFile(), content)
        file.toUri().toURL()
      }
    },
    teardown = { urls =>
      urls.foreach(url => IO.delete(new File(url.toURI())))
      ()
    }
  )

}
