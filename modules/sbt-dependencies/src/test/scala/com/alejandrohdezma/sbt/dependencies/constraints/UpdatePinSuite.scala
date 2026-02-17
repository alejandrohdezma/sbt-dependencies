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
import sbt.util.Level

import com.alejandrohdezma.sbt.dependencies.TestLogger

class UpdatePinSuite extends munit.FunSuite {

  implicit val logger: TestLogger = TestLogger()

  override def beforeEach(context: BeforeEach): Unit = logger.cleanLogs()

  // --- HOCON parsing tests ---

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.scala-lang", artifactId = "scala3-compiler", version = { exact = "3.8.2" } }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with exact version pattern") { urls =>
    val pins = UpdatePin.loadFromUrls(urls)

    val expected = UpdatePin(
      groupId = "org.scala-lang",
      artifactId = Some("scala3-compiler"),
      version = Some(VersionPattern(exact = Some("3.8.2")))
    )

    assertEquals(pins, List(expected))
  }

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.scala-lang", artifactId = "scala-compiler", version = "2.13." }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses version string as prefix shorthand") { urls =>
    val pins = UpdatePin.loadFromUrls(urls)

    val expected = UpdatePin(
      groupId = "org.scala-lang",
      artifactId = Some("scala-compiler"),
      version = Some(VersionPattern(prefix = Some("2.13.")))
    )

    assertEquals(pins, List(expected))
  }

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.example", version = { suffix = "-M1" } }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with suffix version pattern") { urls =>
    val pins = UpdatePin.loadFromUrls(urls)

    val expected = UpdatePin(
      groupId = "org.example",
      version = Some(VersionPattern(suffix = Some("-M1")))
    )

    assertEquals(pins, List(expected))
  }

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.example", version = { contains = "rc" } }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with contains version pattern") { urls =>
    val pins = UpdatePin.loadFromUrls(urls)

    val expected = UpdatePin(
      groupId = "org.example",
      version = Some(VersionPattern(contains = Some("rc")))
    )

    assertEquals(pins, List(expected))
  }

  withPinFile {
    """updates.pin = [
      |  { groupId = "com.typesafe.akka" }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with only groupId") { urls =>
    val pins = UpdatePin.loadFromUrls(urls)

    val expected = UpdatePin(groupId = "com.typesafe.akka")

    assertEquals(pins, List(expected))
  }

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.scala-lang", artifactId = "scala3-compiler" }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with groupId and artifactId") { urls =>
    val pins = UpdatePin.loadFromUrls(urls)

    val expected = UpdatePin(
      groupId = "org.scala-lang",
      artifactId = Some("scala3-compiler")
    )

    assertEquals(pins, List(expected))
  }

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.scala-lang", artifactId = "scala3-compiler", version = { exact = "3.8.2" } },
      |  { groupId = "com.typesafe.akka" },
      |  { groupId = "org.example", version = "1.0." }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses multiple entries") { urls =>
    val pins = UpdatePin.loadFromUrls(urls)

    val expected = List(
      UpdatePin("org.scala-lang", Some("scala3-compiler"), Some(VersionPattern(exact = Some("3.8.2")))),
      UpdatePin("com.typesafe.akka"),
      UpdatePin("org.example", version = Some(VersionPattern(prefix = Some("1.0."))))
    )

    assertEquals(pins, expected)
  }

  withPinFile {
    """something = [
      |  { foo = bar }
      |]
      |""".stripMargin
  }.test("loadFromUrls returns empty for files without updates.pin key") { urls =>
    val pins = UpdatePin.loadFromUrls(urls)

    assertEquals(pins, Nil)
  }

  withPinFile {
    """updates.pin = [
      |  { artifactId = "some-artifact" }
      |]
      |""".stripMargin
  }.test("loadFromUrls warns and skips entry with missing groupId") { urls =>
    val pins = UpdatePin.loadFromUrls(urls)

    assertEquals(pins, Nil)
    assert(logger.getLogs(Level.Warn).exists(_.contains("entry at index 0 must have a 'groupId'")))
  }

  test("loadFromUrls returns empty list for empty URL list") {
    val result = UpdatePin.loadFromUrls(Nil)

    assertEquals(result, Nil)
  }

  test("loadFromUrls can load Scala Steward's default config without crash") {
    val pins = UpdatePin.loadFromUrls(UpdatePin.default)

    assert(pins.size >= 0)
  }

  // --- matchesArtifact tests ---

  test("matchesArtifact returns true for matching groupId and artifactId") {
    val pin = UpdatePin("org.http4s", Some("http4s-core"))

    assert(pin.matchesArtifact("org.http4s", "http4s-core"))
  }

  test("matchesArtifact returns false for non-matching groupId") {
    val pin = UpdatePin("org.http4s", Some("http4s-core"))

    assert(!pin.matchesArtifact("org.typelevel", "http4s-core"))
  }

  test("matchesArtifact returns true for any artifact when artifactId is None") {
    val pin = UpdatePin("org.http4s")

    assert(pin.matchesArtifact("org.http4s", "http4s-core"))
    assert(pin.matchesArtifact("org.http4s", "http4s-dsl"))
  }

  test("matchesArtifact returns false for non-matching artifactId") {
    val pin = UpdatePin("org.http4s", Some("http4s-core"))

    assert(!pin.matchesArtifact("org.http4s", "http4s-dsl"))
  }

  // --- matchesVersion tests ---

  test("matchesVersion returns true for matching version") {
    val pin = UpdatePin("org.http4s", version = Some(VersionPattern(prefix = Some("0.23."))))

    assert(pin.matchesVersion("0.23.10"))
  }

  test("matchesVersion returns false for non-matching version") {
    val pin = UpdatePin("org.http4s", version = Some(VersionPattern(prefix = Some("0.23."))))

    assert(!pin.matchesVersion("1.0.0"))
  }

  test("matchesVersion returns true when version pattern is None") {
    val pin = UpdatePin("org.http4s")

    assert(pin.matchesVersion("1.0.0"))
    assert(pin.matchesVersion("0.23.10"))
  }

  //////////////
  // Fixtures //
  //////////////

  /** Creates a `FunFixture` that writes each content string to a temporary HOCON file and provides the URLs to the
    * test. Files are deleted after the test completes.
    */
  def withPinFile(contents: String*) = FunFixture[List[URL]](
    setup = { _ =>
      contents.toList.map { content =>
        val file = Files.createTempFile("pins", ".conf")
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
