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

package com.alejandrohdezma.sbt.dependencies.finders

import java.nio.file.Files

import sbt.IO
import sbt.util.Level

import com.alejandrohdezma.sbt.dependencies.TestLogger
import com.alejandrohdezma.sbt.dependencies.constraints.ConfigCache
import com.alejandrohdezma.sbt.dependencies.model.Dependency

class RetractionFinderSuite extends munit.FunSuite {

  implicit val logger: TestLogger = TestLogger()

  private val tempCacheDir = Files.createTempDirectory("config-cache")

  implicit val configCache: ConfigCache = ConfigCache(tempCacheDir.toFile())

  override def afterAll(): Unit = IO.delete(tempCacheDir.toFile())

  override def beforeEach(context: BeforeEach): Unit = logger.cleanLogs()

  // --- isRetracted tests ---

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
  }.test("fromUrls marks matching version as retracted") { finder =>
    assert(finder.isRetracted("org.scala-lang", "scala3-compiler", "3.8.2"))
  }

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
  }.test("fromUrls does not retract non-matching version") { finder =>
    assert(!finder.isRetracted("org.scala-lang", "scala3-compiler", "3.8.1"))
  }

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
  }.test("fromUrls does not retract non-matching dependency") { finder =>
    assert(!finder.isRetracted("org.typelevel", "cats-core", "2.0.0"))
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
  }.test("fromUrls retracts all artifacts and versions for groupId-only entry") { finder =>
    assert(finder.isRetracted("com.typesafe.akka", "akka-actor", "2.6.0"))
    assert(finder.isRetracted("com.typesafe.akka", "akka-stream", "2.7.0"))
  }

  test("fromUrls with empty URL list returns finder that never retracts") {
    val finder = RetractionFinder.fromUrls(Nil)

    assert(!finder.isRetracted("org.scala-lang", "scala3-compiler", "3.8.2"))
  }

  // --- warnIfRetracted tests ---

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
  }.test("warnIfRetracted logs warning for retracted version") { finder =>
    val dependency = Dependency.WithNumericVersion(
      organization = "org.scala-lang",
      name = "scala3-compiler",
      version = Dependency.Version.Numeric(List(3, 8, 2), None, Dependency.Version.Numeric.Marker.NoMarker),
      isCross = true
    )

    finder.warnIfRetracted(dependency)

    assertEquals(logger.getLogs(Level.Warn).size, 1)
    assert(logger.getLogs(Level.Warn).head.contains("3.8.2 is retracted"))
    assert(logger.getLogs(Level.Warn).head.contains("Critical bug"))
    assert(logger.getLogs(Level.Warn).head.contains("https://example.com/bug"))
  }

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
  }.test("warnIfRetracted does not log for non-retracted version") { finder =>
    val dependency = Dependency.WithNumericVersion(
      organization = "org.scala-lang",
      name = "scala3-compiler",
      version = Dependency.Version.Numeric(List(3, 8, 1), None, Dependency.Version.Numeric.Marker.NoMarker),
      isCross = true
    )

    finder.warnIfRetracted(dependency)

    assertEquals(logger.getLogs(Level.Warn), Nil)
  }

  /** Creates a `FunFixture` that writes the content to a temporary HOCON file and provides a `RetractionFinder` loaded
    * from it.
    */
  def withRetractionFile(contents: String) = FunFixture[RetractionFinder](
    setup = { _ =>
      val file = Files.createTempFile("retractions", ".conf")
      IO.write(file.toFile(), contents)
      RetractionFinder.fromUrls(List(file.toUri().toURL()))
    },
    teardown = _ => ()
  )

}
