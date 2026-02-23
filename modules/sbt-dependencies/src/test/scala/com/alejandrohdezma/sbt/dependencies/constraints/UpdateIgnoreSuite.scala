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

class UpdateIgnoreSuite extends munit.FunSuite {

  implicit val logger: TestLogger = TestLogger()

  private val tempCacheDir = Files.createTempDirectory("config-cache")

  implicit val configCache: ConfigCache = ConfigCache(tempCacheDir.toFile())

  override def afterAll(): Unit = IO.delete(tempCacheDir.toFile())

  override def beforeEach(context: BeforeEach): Unit = logger.cleanLogs()

  // --- HOCON parsing tests ---

  withIgnoreFile {
    """updates.ignore = [
      |  { groupId = "org.scala-lang", artifactId = "scala3-compiler", version = { exact = "3.8.2" } }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with exact version pattern") { urls =>
    val ignores = UpdateIgnore.loadFromUrls(urls)

    val expected = UpdateIgnore(
      groupId = "org.scala-lang",
      artifactId = Some("scala3-compiler"),
      version = Some(VersionPattern(exact = Some("3.8.2")))
    )

    assertEquals(ignores, List(expected))
  }

  withIgnoreFile {
    """updates.ignore = [
      |  { groupId = "org.scala-lang", artifactId = "scala-compiler", version = "2.13." }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses version string as prefix shorthand") { urls =>
    val ignores = UpdateIgnore.loadFromUrls(urls)

    val expected = UpdateIgnore(
      groupId = "org.scala-lang",
      artifactId = Some("scala-compiler"),
      version = Some(VersionPattern(prefix = Some("2.13.")))
    )

    assertEquals(ignores, List(expected))
  }

  withIgnoreFile {
    """updates.ignore = [
      |  { groupId = "org.example", version = { suffix = "-M1" } }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with suffix version pattern") { urls =>
    val ignores = UpdateIgnore.loadFromUrls(urls)

    val expected = UpdateIgnore(
      groupId = "org.example",
      version = Some(VersionPattern(suffix = Some("-M1")))
    )

    assertEquals(ignores, List(expected))
  }

  withIgnoreFile {
    """updates.ignore = [
      |  { groupId = "org.example", version = { contains = "rc" } }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with contains version pattern") { urls =>
    val ignores = UpdateIgnore.loadFromUrls(urls)

    val expected = UpdateIgnore(
      groupId = "org.example",
      version = Some(VersionPattern(contains = Some("rc")))
    )

    assertEquals(ignores, List(expected))
  }

  withIgnoreFile {
    """updates.ignore = [
      |  { groupId = "com.typesafe.akka" }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with only groupId") { urls =>
    val ignores = UpdateIgnore.loadFromUrls(urls)

    val expected = UpdateIgnore(groupId = "com.typesafe.akka")

    assertEquals(ignores, List(expected))
  }

  withIgnoreFile {
    """updates.ignore = [
      |  { groupId = "org.scala-lang", artifactId = "scala3-compiler" }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with groupId and artifactId") { urls =>
    val ignores = UpdateIgnore.loadFromUrls(urls)

    val expected = UpdateIgnore(
      groupId = "org.scala-lang",
      artifactId = Some("scala3-compiler")
    )

    assertEquals(ignores, List(expected))
  }

  withIgnoreFile {
    """updates.ignore = [
      |  { groupId = "org.scala-lang", artifactId = "scala3-compiler", version = { exact = "3.8.2" } },
      |  { groupId = "com.typesafe.akka" },
      |  { groupId = "org.example", version = "1.0." }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses multiple entries") { urls =>
    val ignores = UpdateIgnore.loadFromUrls(urls)

    val expected = List(
      UpdateIgnore("org.scala-lang", Some("scala3-compiler"), Some(VersionPattern(exact = Some("3.8.2")))),
      UpdateIgnore("com.typesafe.akka"),
      UpdateIgnore("org.example", version = Some(VersionPattern(prefix = Some("1.0."))))
    )

    assertEquals(ignores, expected)
  }

  withIgnoreFile {
    """something = [
      |  { foo = bar }
      |]
      |""".stripMargin
  }.test("loadFromUrls returns empty for files without updates.ignore key") { urls =>
    val ignores = UpdateIgnore.loadFromUrls(urls)

    assertEquals(ignores, Nil)
  }

  withIgnoreFile {
    """updates.ignore = [
      |  { artifactId = "some-artifact" }
      |]
      |""".stripMargin
  }.test("loadFromUrls warns and skips entry with missing groupId") { urls =>
    val ignores = UpdateIgnore.loadFromUrls(urls)

    assertEquals(ignores, Nil)

    val expectedLogs = List(
      s"⚠ Skipping malformed ${UpdateIgnore.name} from $CYAN${urls.head}$RESET: entry at index 0: " +
        "must have a 'groupId'"
    )

    assertEquals(logger.getLogs(Level.Warn), expectedLogs)
  }

  test("loadFromUrls returns empty list for empty URL list") {
    val result = UpdateIgnore.loadFromUrls(Nil)

    assertEquals(result, Nil)
  }

  test("loadFromUrls can load Scala Steward's default config") {
    val ignores = UpdateIgnore.loadFromUrls(UpdateIgnore.default)

    assertEquals(ignores.nonEmpty, true)
  }

  // --- Matching tests ---

  test("matches returns true when all fields match") {
    val ignore = UpdateIgnore("org.scala-lang", Some("scala3-compiler"), Some(VersionPattern(exact = Some("3.8.2"))))

    assertEquals(ignore.matches("org.scala-lang", "scala3-compiler", "3.8.2"), true)
  }

  test("matches returns false for non-matching groupId") {
    val ignore = UpdateIgnore("org.scala-lang")

    assertEquals(ignore.matches("org.typelevel", "cats-core", "2.0.0"), false)
  }

  test("matches returns true for any artifact when artifactId is None") {
    val ignore = UpdateIgnore("com.typesafe.akka")

    assertEquals(ignore.matches("com.typesafe.akka", "akka-actor", "2.6.0"), true)
    assertEquals(ignore.matches("com.typesafe.akka", "akka-stream", "2.6.0"), true)
  }

  test("matches returns false for non-matching artifactId") {
    val ignore = UpdateIgnore("org.scala-lang", Some("scala3-compiler"))

    assertEquals(ignore.matches("org.scala-lang", "scala-library", "2.13.0"), false)
  }

  test("matches returns true for any version when version is None") {
    val ignore = UpdateIgnore("org.scala-lang", Some("scala3-compiler"))

    assertEquals(ignore.matches("org.scala-lang", "scala3-compiler", "3.0.0"), true)
    assertEquals(ignore.matches("org.scala-lang", "scala3-compiler", "3.8.2"), true)
  }

  test("matches returns false for non-matching version") {
    val ignore = UpdateIgnore("org.scala-lang", Some("scala3-compiler"), Some(VersionPattern(exact = Some("3.8.2"))))

    assertEquals(ignore.matches("org.scala-lang", "scala3-compiler", "3.8.1"), false)
  }

  //////////////
  // Fixtures //
  //////////////

  /** Creates a `FunFixture` that writes each content string to a temporary HOCON file and provides the URLs to the
    * test. Files are deleted after the test completes.
    */
  def withIgnoreFile(contents: String*) = FunFixture[List[URL]](
    setup = { _ =>
      contents.toList.map { content =>
        val file = Files.createTempFile("ignores", ".conf")
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
