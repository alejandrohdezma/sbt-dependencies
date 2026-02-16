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

import java.nio.file.Files

import sbt.IO
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version

class MigrationFinderSuite extends munit.FunSuite {

  implicit val logger: Logger = TestLogger()

  private def dep(org: String, name: String) = Dependency.WithNumericVersion(
    org,
    name,
    Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
    isCross = true
  )

  def withMigrationFile(contents: String) = FunFixture[MigrationFinder](
    setup = { _ =>
      val file = Files.createTempFile("migrations", ".conf")
      IO.write(file.toFile(), contents)
      MigrationFinder.fromUrls(List(file.toUri().toURL()))
    },
    teardown = _ => ()
  )

  // --- fromUrls tests ---

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdBefore = com.typesafe.play
      |    groupIdAfter = org.playframework
      |    artifactIdAfter = play-json
      |  }
      |]
      |""".stripMargin
  }.test("fromUrls finds migration for matching dependency") { finder =>
    val result = finder.findMigration(dep("com.typesafe.play", "play-json"))

    val expected = ArtifactMigration(
      groupIdBefore = Some("com.typesafe.play"),
      groupIdAfter = "org.playframework",
      artifactIdBefore = None,
      artifactIdAfter = "play-json"
    )

    assertEquals(result, Some(expected))
  }

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdBefore = com.typesafe.play
      |    groupIdAfter = org.playframework
      |    artifactIdAfter = play-json
      |  }
      |]
      |""".stripMargin
  }.test("fromUrls returns None for non-matching dependency") { finder =>
    val result = finder.findMigration(dep("org.other", "other-lib"))

    assertEquals(result, None)
  }

  withMigrationFile {
    """changes = [
      |  {
      |    groupIdBefore = com.typesafe.play
      |    groupIdAfter = org.playframework
      |    artifactIdAfter = play-json
      |  },
      |  {
      |    groupIdAfter = org.typelevel
      |    artifactIdBefore = cats-core
      |    artifactIdAfter = cats-core-new
      |  }
      |]
      |""".stripMargin
  }.test("fromUrls finds first matching migration from multiple") { finder =>
    val result = finder.findMigration(dep("org.typelevel", "cats-core"))

    val expected = ArtifactMigration(
      groupIdBefore = None,
      groupIdAfter = "org.typelevel",
      artifactIdBefore = Some("cats-core"),
      artifactIdAfter = "cats-core-new"
    )

    assertEquals(result, Some(expected))
  }

  test("fromUrls with empty URL list returns finder that never matches") {
    val finder = MigrationFinder.fromUrls(Nil)

    assertEquals(finder.findMigration(dep("com.any", "any-lib")), None)
  }

}
