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

import sbt.IO
import sbt.util.Level

import com.alejandrohdezma.sbt.dependencies.TestLogger

class RetractedArtifactSuite extends munit.FunSuite {

  implicit val logger: TestLogger = TestLogger()

  private val tempCacheDir = Files.createTempDirectory("config-cache")

  implicit val configCache: ConfigCache = ConfigCache(tempCacheDir.toFile())

  override def afterAll(): Unit = IO.delete(tempCacheDir.toFile())

  override def beforeEach(context: BeforeEach): Unit = logger.cleanLogs()

  // --- HOCON parsing tests ---

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    reason = "Critical bug"
      |    doc = "https://example.com/bug"
      |    artifacts = [
      |      { groupId = "org.scala-lang", artifactId = "scala3-compiler", version = { exact = "3.8.2" } }
      |    ]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with exact version pattern") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    val expected = RetractedArtifact(
      reason = "Critical bug", doc = "https://example.com/bug", groupId = "org.scala-lang",
      artifactId = Some("scala3-compiler"), version = Some(VersionPattern(exact = Some("3.8.2")))
    )

    assertEquals(retractions, List(expected))
  }

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    reason = "Broken binary compat"
      |    doc = "https://example.com/compat"
      |    artifacts = [
      |      { groupId = "org.scala-lang", artifactId = "scala-compiler", version = "2.13." }
      |    ]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses version string as prefix shorthand") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    val expected = RetractedArtifact(
      reason = "Broken binary compat", doc = "https://example.com/compat", groupId = "org.scala-lang",
      artifactId = Some("scala-compiler"), version = Some(VersionPattern(prefix = Some("2.13.")))
    )

    assertEquals(retractions, List(expected))
  }

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    reason = "Milestone release"
      |    doc = "https://example.com/milestone"
      |    artifacts = [
      |      { groupId = "org.example", version = { suffix = "-M1" } }
      |    ]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with suffix version pattern") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    val expected = RetractedArtifact(
      reason = "Milestone release",
      doc = "https://example.com/milestone",
      groupId = "org.example",
      version = Some(VersionPattern(suffix = Some("-M1")))
    )

    assertEquals(retractions, List(expected))
  }

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    reason = "Release candidate"
      |    doc = "https://example.com/rc"
      |    artifacts = [
      |      { groupId = "org.example", version = { contains = "rc" } }
      |    ]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with contains version pattern") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    val expected = RetractedArtifact(
      reason = "Release candidate",
      doc = "https://example.com/rc",
      groupId = "org.example",
      version = Some(VersionPattern(contains = Some("rc")))
    )

    assertEquals(retractions, List(expected))
  }

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    reason = "All versions bad"
      |    doc = "https://example.com/all"
      |    artifacts = [
      |      { groupId = "com.typesafe.akka" }
      |    ]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with only groupId") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    val expected = RetractedArtifact(
      reason = "All versions bad",
      doc = "https://example.com/all",
      groupId = "com.typesafe.akka"
    )

    assertEquals(retractions, List(expected))
  }

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    reason = "Specific artifact"
      |    doc = "https://example.com/artifact"
      |    artifacts = [
      |      { groupId = "org.scala-lang", artifactId = "scala3-compiler" }
      |    ]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with groupId and artifactId") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    val expected = RetractedArtifact(
      reason = "Specific artifact",
      doc = "https://example.com/artifact",
      groupId = "org.scala-lang",
      artifactId = Some("scala3-compiler")
    )

    assertEquals(retractions, List(expected))
  }

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    reason = "Critical bug"
      |    doc = "https://example.com/bug"
      |    artifacts = [
      |      { groupId = "org.scala-lang", artifactId = "scala3-compiler", version = { exact = "3.8.2" } }
      |    ]
      |  },
      |  {
      |    reason = "Security issue"
      |    doc = "https://example.com/sec"
      |    artifacts = [
      |      { groupId = "com.typesafe.akka" },
      |      { groupId = "org.example", version = "1.0." }
      |    ]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses multiple entries") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    val expected = List(
      RetractedArtifact(
        reason = "Critical bug", doc = "https://example.com/bug", groupId = "org.scala-lang",
        artifactId = Some("scala3-compiler"), version = Some(VersionPattern(exact = Some("3.8.2")))
      ),
      RetractedArtifact(
        reason = "Security issue",
        doc = "https://example.com/sec",
        groupId = "com.typesafe.akka"
      ),
      RetractedArtifact(
        reason = "Security issue", doc = "https://example.com/sec", groupId = "org.example", artifactId = None,
        version = Some(VersionPattern(prefix = Some("1.0.")))
      )
    )

    assertEquals(retractions, expected)
  }

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    reason = "Shared reason"
      |    doc = "https://example.com/shared"
      |    artifacts = [
      |      { groupId = "org.scala-lang", artifactId = "scala3-compiler" },
      |      { groupId = "org.scala-lang", artifactId = "scala3-library" }
      |    ]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls flattens multiple artifacts from single retracted entry") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    val expected = List(
      RetractedArtifact(
        reason = "Shared reason",
        doc = "https://example.com/shared",
        groupId = "org.scala-lang",
        artifactId = Some("scala3-compiler")
      ),
      RetractedArtifact(
        reason = "Shared reason",
        doc = "https://example.com/shared",
        groupId = "org.scala-lang",
        artifactId = Some("scala3-library")
      )
    )

    assertEquals(retractions, expected)
  }

  withRetractionFile {
    """something = [
      |  { foo = bar }
      |]
      |""".stripMargin
  }.test("loadFromUrls returns empty for files without updates.retracted key") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    assertEquals(retractions, Nil)
  }

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    doc = "https://example.com/doc"
      |    artifacts = [
      |      { groupId = "org.example" }
      |    ]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls warns and skips entry with missing reason") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    assertEquals(retractions, Nil)
    assertEquals(
      logger.getLogs(Level.Warn),
      List(
        s"⚠ Skipping malformed ${RetractedArtifact.name} from $CYAN${urls.head}$RESET: entry at index 0: " +
          "must have a 'reason'"
      )
    )
  }

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    reason = "Some reason"
      |    artifacts = [
      |      { groupId = "org.example" }
      |    ]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls warns and skips entry with missing doc") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    assertEquals(retractions, Nil)
    assertEquals(
      logger.getLogs(Level.Warn),
      List(
        s"⚠ Skipping malformed ${RetractedArtifact.name} from $CYAN${urls.head}$RESET: entry at index 0: " +
          "must have a 'doc'"
      )
    )
  }

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    reason = "Some reason"
      |    doc = "https://example.com/doc"
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls warns and skips entry with missing artifacts") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    assertEquals(retractions, Nil)
    assertEquals(
      logger.getLogs(Level.Warn),
      List(
        s"⚠ Skipping malformed ${RetractedArtifact.name} from $CYAN${urls.head}$RESET: entry at index 0: " +
          "must have a 'artifacts' array"
      )
    )
  }

  withRetractionFile {
    """updates.retracted = [
      |  {
      |    reason = "Some reason"
      |    doc = "https://example.com/doc"
      |    artifacts = [
      |      { artifactId = "some-artifact" }
      |    ]
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls warns and skips artifact with missing groupId") { urls =>
    val retractions = RetractedArtifact.loadFromUrls(urls)

    assertEquals(retractions, Nil)
    assertEquals(
      logger.getLogs(Level.Warn),
      List(
        s"⚠ Skipping malformed ${RetractedArtifact.name} from $CYAN${urls.head}$RESET: entry at index 0: " +
          "entry at index 0: must have a 'groupId'"
      )
    )
  }

  test("loadFromUrls returns empty list for empty URL list") {
    val result = RetractedArtifact.loadFromUrls(Nil)

    assertEquals(result, Nil)
  }

  test("loadFromUrls can load Scala Steward's default config") {
    val retractions = RetractedArtifact.loadFromUrls(RetractedArtifact.default)

    assertEquals(retractions.nonEmpty, true)
  }

  // --- Matching tests ---

  test("matches returns true when all fields match") {
    val retracted = RetractedArtifact(
      reason = "bug", doc = "https://doc", groupId = "org.scala-lang", artifactId = Some("scala3-compiler"),
      version = Some(VersionPattern(exact = Some("3.8.2")))
    )

    assertEquals(retracted.matches("org.scala-lang", "scala3-compiler", "3.8.2"), true)
  }

  test("matches returns false for non-matching groupId") {
    val retracted = RetractedArtifact(reason = "bug", doc = "https://doc", groupId = "org.scala-lang")

    assertEquals(retracted.matches("org.typelevel", "cats-core", "2.0.0"), false)
  }

  test("matches returns true for any artifact when artifactId is None") {
    val retracted = RetractedArtifact(reason = "bug", doc = "https://doc", groupId = "com.typesafe.akka")

    assertEquals(retracted.matches("com.typesafe.akka", "akka-actor", "2.6.0"), true)
    assertEquals(retracted.matches("com.typesafe.akka", "akka-stream", "2.6.0"), true)
  }

  test("matches returns false for non-matching artifactId") {
    val retracted = RetractedArtifact(
      reason = "bug",
      doc = "https://doc",
      groupId = "org.scala-lang",
      artifactId = Some("scala3-compiler")
    )

    assertEquals(retracted.matches("org.scala-lang", "scala-library", "2.13.0"), false)
  }

  test("matches returns true for any version when version is None") {
    val retracted = RetractedArtifact(
      reason = "bug",
      doc = "https://doc",
      groupId = "org.scala-lang",
      artifactId = Some("scala3-compiler")
    )

    assertEquals(retracted.matches("org.scala-lang", "scala3-compiler", "3.0.0"), true)
    assertEquals(retracted.matches("org.scala-lang", "scala3-compiler", "3.8.2"), true)
  }

  test("matches returns false for non-matching version") {
    val retracted = RetractedArtifact(
      reason = "bug", doc = "https://doc", groupId = "org.scala-lang", artifactId = Some("scala3-compiler"),
      version = Some(VersionPattern(exact = Some("3.8.2")))
    )

    assertEquals(retracted.matches("org.scala-lang", "scala3-compiler", "3.8.1"), false)
  }

  //////////////
  // Fixtures //
  //////////////

  /** Creates a `FunFixture` that writes each content string to a temporary HOCON file and provides the URLs to the
    * test. Files are deleted after the test completes.
    */
  def withRetractionFile(contents: String*) = FunFixture[List[URL]](
    setup = { _ =>
      contents.toList.map { content =>
        val file = Files.createTempFile("retractions", ".conf")
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
