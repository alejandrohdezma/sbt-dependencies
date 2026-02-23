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

import scala.Console._
import scala.util.Try

import sbt.IO
import sbt.util.Level

import com.typesafe.config.ConfigFactory

import com.alejandrohdezma.sbt.dependencies.TestLogger
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version

class ArtifactMigrationSuite extends munit.FunSuite {

  implicit val logger: TestLogger = TestLogger()

  private val tempCacheDir = Files.createTempDirectory("config-cache")

  implicit val configCache: ConfigCache = ConfigCache(tempCacheDir.toFile())

  override def afterAll(): Unit = IO.delete(tempCacheDir.toFile())

  override def beforeEach(context: BeforeEach): Unit = logger.cleanLogs()

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

  // --- HOCON parsing tests ---

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdBefore = com.typesafe.play
      |    groupIdAfter = org.playframework
      |    artifactIdAfter = play-json
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses group-only change") { files =>
    val migrations = ArtifactMigration.loadFromUrls(files)

    val expected = List(
      ArtifactMigration(Some("com.typesafe.play"), "org.playframework", None, "play-json")
    )

    assertEquals(migrations, expected)
  }

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdAfter = org.typelevel
      |    artifactIdBefore = cats-core
      |    artifactIdAfter = cats-core-new
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses artifact-only change") { files =>
    val migrations = ArtifactMigration.loadFromUrls(files)

    val expected = List(
      ArtifactMigration(None, "org.typelevel", Some("cats-core"), "cats-core-new")
    )

    assertEquals(migrations, expected)
  }

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdBefore = com.old.group
      |    groupIdAfter = com.new.group
      |    artifactIdBefore = old-name
      |    artifactIdAfter = new-name
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses both group and artifact change") { files =>
    val migrations = ArtifactMigration.loadFromUrls(files)

    val expected = List(
      ArtifactMigration(Some("com.old.group"), "com.new.group", Some("old-name"), "new-name")
    )

    assertEquals(migrations, expected)
  }

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdBefore = com.old1
      |    groupIdAfter = com.new1
      |    artifactIdAfter = artifact1
      |  },
      |  {
      |    groupIdBefore = com.old2
      |    groupIdAfter = com.new2
      |    artifactIdAfter = artifact2
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses multiple migrations from single file") { urls =>
    val migrations = ArtifactMigration.loadFromUrls(urls)

    val expected = List(
      ArtifactMigration(Some("com.old1"), "com.new1", None, "artifact1"),
      ArtifactMigration(Some("com.old2"), "com.new2", None, "artifact2")
    )

    assertEquals(migrations, expected)
  }

  withMigrationFile(
    """changes = [
      |  {
      |    groupIdBefore = com.first
      |    groupIdAfter = com.first.new
      |    artifactIdAfter = artifact1
      |  }
      |]
      |""".stripMargin,
    """changes = [
      |  {
      |    groupIdBefore = com.second
      |    groupIdAfter = com.second.new
      |    artifactIdAfter = artifact2
      |  },
      |  {
      |    groupIdBefore = com.third
      |    groupIdAfter = com.third.new
      |    artifactIdAfter = artifact3
      |  }
      |]
      |""".stripMargin
  ).test("loadFromUrls combines migrations from multiple URLs") { urls =>
    val migrations = ArtifactMigration.loadFromUrls(urls)

    val expected = List(
      ArtifactMigration(Some("com.first"), "com.first.new", None, "artifact1"),
      ArtifactMigration(Some("com.second"), "com.second.new", None, "artifact2"),
      ArtifactMigration(Some("com.third"), "com.third.new", None, "artifact3")
    )

    assertEquals(migrations, expected)
  }

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdAfter = org.playframework
      |    artifactIdAfter = play-json
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls warns and skips when entry missing both groupIdBefore and artifactIdBefore") { urls =>
    val migrations = ArtifactMigration.loadFromUrls(urls)

    assertEquals(migrations, Nil)

    val expectedLogs = List(
      s"⚠ Skipping malformed ${ArtifactMigration.name} from $CYAN${urls.head}$RESET: entry at index 0: " +
        s"must have at least one of 'groupIdBefore' or 'artifactIdBefore'"
    )

    assertEquals(logger.getLogs(Level.Warn), expectedLogs)
  }

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdBefore = com.old
      |    artifactIdAfter = play-json
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls warns and skips when entry missing groupIdAfter") { urls =>
    val migrations = ArtifactMigration.loadFromUrls(urls)

    assertEquals(migrations, Nil)

    val expectedLogs = List(
      s"⚠ Skipping malformed ${ArtifactMigration.name} from $CYAN${urls.head}$RESET: entry at index 0: " +
        s"must have a 'groupIdAfter'"
    )

    assertEquals(logger.getLogs(Level.Warn), expectedLogs)
  }

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdBefore = com.old
      |    groupIdAfter = com.new
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls warns and skips when entry missing artifactIdAfter") { urls =>
    val migrations = ArtifactMigration.loadFromUrls(urls)

    assertEquals(migrations, Nil)

    val expectedLogs = List(
      s"⚠ Skipping malformed ${ArtifactMigration.name} from $CYAN${urls.head}$RESET: entry at index 0: " +
        s"must have a 'artifactIdAfter'"
    )

    assertEquals(logger.getLogs(Level.Warn), expectedLogs)
  }

  withMigrationFile("not valid hocon {{{").test("loadFromUrls warns and skips for invalid HOCON") { urls =>
    val migrations = ArtifactMigration.loadFromUrls(urls)

    val parseError = Try(ConfigFactory.parseURL(urls.head)).failed.get.getMessage

    val expectedLogs = List(s"Failed to parse config from ${urls.head}: $parseError")

    assertEquals(migrations, Nil: List[ArtifactMigration])
    assertEquals(logger.getLogs(Level.Warn), expectedLogs)
  }

  withMigrationFile {
    """something = [
      |  { foo = bar }
      |]
      |""".stripMargin
  }.test("loadFromUrls warns and skips when changes key is missing") { urls =>
    val migrations = ArtifactMigration.loadFromUrls(urls)

    assertEquals(migrations, Nil)

    val expectedLogs = List(
      s"⚠ Skipping malformed ${ArtifactMigration.name} from $CYAN${urls.head}$RESET: " +
        s"must have a 'changes' array"
    )

    assertEquals(logger.getLogs(Level.Warn), expectedLogs)
  }

  test("loadFromUrls returns empty list for empty URL list") {
    val result = ArtifactMigration.loadFromUrls(Nil)

    assertEquals(result, Nil)
  }

  test("loadFromUrls can load Scala Steward's default migration file") {
    val migrations = ArtifactMigration.loadFromUrls(ArtifactMigration.default)

    assertEquals(migrations.nonEmpty, true)
  }

  // --- Matching tests ---

  private def dep(org: String, name: String) = Dependency.WithNumericVersion(
    org,
    name,
    Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
    isCross = true
  )

  test("matches returns true for group-only migration") {
    val migration = ArtifactMigration(
      groupIdBefore = Some("com.typesafe.play"),
      groupIdAfter = "org.playframework",
      artifactIdBefore = None,
      artifactIdAfter = "play-json"
    )

    assertEquals(migration.matches(dep("com.typesafe.play", "play-json")), true)
  }

  test("matches returns true for artifact-only migration") {
    val migration = ArtifactMigration(
      groupIdBefore = None,
      groupIdAfter = "org.typelevel",
      artifactIdBefore = Some("cats-core"),
      artifactIdAfter = "cats-core-new"
    )

    assertEquals(migration.matches(dep("org.typelevel", "cats-core")), true)
  }

  test("matches returns true for both group and artifact migration") {
    val migration = ArtifactMigration(
      groupIdBefore = Some("com.old"),
      groupIdAfter = "com.new",
      artifactIdBefore = Some("old-name"),
      artifactIdAfter = "new-name"
    )

    assertEquals(migration.matches(dep("com.old", "old-name")), true)
  }

  test("matches returns false for non-matching group") {
    val migration = ArtifactMigration(
      groupIdBefore = Some("com.typesafe.play"),
      groupIdAfter = "org.playframework",
      artifactIdBefore = None,
      artifactIdAfter = "play-json"
    )

    assertEquals(migration.matches(dep("com.other.group", "play-json")), false)
  }

  test("matches returns false for non-matching artifact") {
    val migration = ArtifactMigration(
      groupIdBefore = Some("com.typesafe.play"),
      groupIdAfter = "org.playframework",
      artifactIdBefore = None,
      artifactIdAfter = "play-json"
    )

    assertEquals(migration.matches(dep("com.typesafe.play", "other-artifact")), false)
  }

  test("matches returns false when artifact does not match artifactIdAfter (group-only migration)") {
    val migration = ArtifactMigration(
      groupIdBefore = Some("com.typesafe.play"),
      groupIdAfter = "org.playframework",
      artifactIdBefore = None,
      artifactIdAfter = "play-json"
    )

    // In group-only migration, artifact must match artifactIdAfter
    assertEquals(migration.matches(dep("com.typesafe.play", "play-ws")), false)
  }

}
