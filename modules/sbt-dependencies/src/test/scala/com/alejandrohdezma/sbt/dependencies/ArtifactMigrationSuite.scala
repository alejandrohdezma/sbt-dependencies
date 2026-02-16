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

package com.alejandrohdezma.sbt.dependencies

import java.io.File
import java.net.URL
import java.nio.file.Files

import sbt.IO
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version

class ArtifactMigrationSuite extends munit.FunSuite {

  implicit val logger: Logger = TestLogger()

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

    assertEquals(migrations.length, 1)
    assertEquals(migrations.head.groupIdBefore, Some("com.typesafe.play"))
    assertEquals(migrations.head.groupIdAfter, "org.playframework")
    assertEquals(migrations.head.artifactIdBefore, None)
    assertEquals(migrations.head.artifactIdAfter, "play-json")
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

    assertEquals(migrations.length, 1)
    assertEquals(migrations.head.groupIdBefore, None)
    assertEquals(migrations.head.groupIdAfter, "org.typelevel")
    assertEquals(migrations.head.artifactIdBefore, Some("cats-core"))
    assertEquals(migrations.head.artifactIdAfter, "cats-core-new")
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

    assertEquals(migrations.length, 1)
    assertEquals(migrations.head.groupIdBefore, Some("com.old.group"))
    assertEquals(migrations.head.groupIdAfter, "com.new.group")
    assertEquals(migrations.head.artifactIdBefore, Some("old-name"))
    assertEquals(migrations.head.artifactIdAfter, "new-name")
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

    assertEquals(migrations.length, 2)
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

    assertEquals(migrations.length, 3)
    assertEquals(migrations.map(_.groupIdBefore), List(Some("com.first"), Some("com.second"), Some("com.third")))
  }

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdAfter = org.playframework
      |    artifactIdAfter = play-json
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls fails when entry missing both groupIdBefore and artifactIdBefore") { urls =>
    val expectedMessage = "Migration entry at index 0 must have at least one of 'groupIdBefore' or 'artifactIdBefore'"

    interceptMessage[RuntimeException](expectedMessage) {
      ArtifactMigration.loadFromUrls(urls)
    }
  }

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdBefore = com.old
      |    artifactIdAfter = play-json
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls fails when entry missing groupIdAfter") { urls =>
    val expectedMessage = "Migration entry at index 0 must have a 'groupIdAfter'"

    interceptMessage[RuntimeException](expectedMessage) {
      ArtifactMigration.loadFromUrls(urls)
    }
  }

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdBefore = com.old
      |    groupIdAfter = com.new
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls fails when entry missing artifactIdAfter") { urls =>
    val expectedMessage = "Migration entry at index 0 must have a 'artifactIdAfter'"

    interceptMessage[RuntimeException](expectedMessage) {
      ArtifactMigration.loadFromUrls(urls)
    }
  }

  withMigrationFile("not valid hocon {{{").test("loadFromUrls fails for invalid HOCON") { urls =>
    val expectedMessage =
      s"Failed to parse migration file ${urls.head}: ${urls.head.toExternalForm().stripPrefix("file:")}: 1: expecting a close parentheses ')' here, not: '{'"

    interceptMessage[RuntimeException](expectedMessage) {
      ArtifactMigration.loadFromUrls(urls)
    }
  }

  withMigrationFile {
    """something = [
      |  { foo = bar }
      |]
      |""".stripMargin
  }.test("loadFromUrls fails when changes key is missing") { urls =>
    val expectedMessage = "Migration file must contain a 'changes' array"

    interceptMessage[RuntimeException](expectedMessage) {
      ArtifactMigration.loadFromUrls(urls)
    }
  }

  test("loadFromUrls returns empty list for empty URL list") {
    val result = ArtifactMigration.loadFromUrls(Nil)

    assertEquals(result, Nil)
  }

  test("loadFromUrls can load Scala Steward's default migration file") {
    val migrations = ArtifactMigration.loadFromUrls(ArtifactMigration.default)

    assert(migrations.nonEmpty)
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

    assert(migration.matches(dep("com.typesafe.play", "play-json")))
  }

  test("matches returns true for artifact-only migration") {
    val migration = ArtifactMigration(
      groupIdBefore = None,
      groupIdAfter = "org.typelevel",
      artifactIdBefore = Some("cats-core"),
      artifactIdAfter = "cats-core-new"
    )

    assert(migration.matches(dep("org.typelevel", "cats-core")))
  }

  test("matches returns true for both group and artifact migration") {
    val migration = ArtifactMigration(
      groupIdBefore = Some("com.old"),
      groupIdAfter = "com.new",
      artifactIdBefore = Some("old-name"),
      artifactIdAfter = "new-name"
    )

    assert(migration.matches(dep("com.old", "old-name")))
  }

  test("matches returns false for non-matching group") {
    val migration = ArtifactMigration(
      groupIdBefore = Some("com.typesafe.play"),
      groupIdAfter = "org.playframework",
      artifactIdBefore = None,
      artifactIdAfter = "play-json"
    )

    assert(!migration.matches(dep("com.other.group", "play-json")))
  }

  test("matches returns false for non-matching artifact") {
    val migration = ArtifactMigration(
      groupIdBefore = Some("com.typesafe.play"),
      groupIdAfter = "org.playframework",
      artifactIdBefore = None,
      artifactIdAfter = "play-json"
    )

    assert(!migration.matches(dep("com.typesafe.play", "other-artifact")))
  }

  test("matches returns false when artifact does not match artifactIdAfter (group-only migration)") {
    val migration = ArtifactMigration(
      groupIdBefore = Some("com.typesafe.play"),
      groupIdAfter = "org.playframework",
      artifactIdBefore = None,
      artifactIdAfter = "play-json"
    )

    // In group-only migration, artifact must match artifactIdAfter
    assert(!migration.matches(dep("com.typesafe.play", "play-ws")))
  }

}
