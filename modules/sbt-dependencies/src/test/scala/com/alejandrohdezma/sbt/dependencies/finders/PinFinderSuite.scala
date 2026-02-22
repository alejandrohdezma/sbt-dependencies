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
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.TestLogger
import com.alejandrohdezma.sbt.dependencies.constraints.ConfigCache

class PinFinderSuite extends munit.FunSuite {

  implicit val logger: Logger = TestLogger()

  private val tempCacheDir = Files.createTempDirectory("config-cache")

  implicit val configCache: ConfigCache = ConfigCache(tempCacheDir.toFile())

  override def afterAll(): Unit = IO.delete(tempCacheDir.toFile())

  // --- fromUrls tests ---

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.http4s", artifactId = "http4s-core", version = "0.23." }
      |]
      |""".stripMargin
  }.test("isAllowed returns true when version matches pin's pattern") { finder =>
    assert(finder.isAllowed("org.http4s", "http4s-core", "0.23.10"))
  }

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.http4s", artifactId = "http4s-core", version = "0.23." }
      |]
      |""".stripMargin
  }.test("isAllowed returns false when version doesn't match pin's pattern") { finder =>
    assert(!finder.isAllowed("org.http4s", "http4s-core", "1.0.0"))
  }

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.http4s", artifactId = "http4s-core", version = "0.23." }
      |]
      |""".stripMargin
  }.test("isAllowed returns true for unpinned artifacts") { finder =>
    assert(finder.isAllowed("org.typelevel", "cats-core", "2.0.0"))
  }

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.http4s", artifactId = "http4s-core" }
      |]
      |""".stripMargin
  }.test("isAllowed returns true when pin has no version constraint") { finder =>
    assert(finder.isAllowed("org.http4s", "http4s-core", "1.0.0"))
    assert(finder.isAllowed("org.http4s", "http4s-core", "0.23.10"))
  }

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.http4s", artifactId = "http4s-core", version = "0.23." }
      |]
      |""".stripMargin
  }.test("isAllowed respects artifactId matching") { finder =>
    assert(!finder.isAllowed("org.http4s", "http4s-core", "1.0.0"))
    assert(finder.isAllowed("org.http4s", "http4s-dsl", "1.0.0"))
  }

  test("fromUrls with empty URL list allows everything") {
    val finder = PinFinder.fromUrls(Nil)

    assert(finder.isAllowed("org.http4s", "http4s-core", "1.0.0"))
  }

  withPinFile {
    """updates.pin = [
      |  { groupId = "org.http4s", version = "0.23." },
      |  { groupId = "org.http4s", artifactId = "http4s-core", version = { prefix = "0.23.1" } }
      |]
      |""".stripMargin
  }.test("multiple pins: version must satisfy ALL matching pins") { finder =>
    assert(finder.isAllowed("org.http4s", "http4s-core", "0.23.10"))
    assert(!finder.isAllowed("org.http4s", "http4s-core", "0.23.5"))
    assert(!finder.isAllowed("org.http4s", "http4s-core", "1.0.0"))
    assert(finder.isAllowed("org.http4s", "http4s-dsl", "0.23.5"))
    assert(!finder.isAllowed("org.http4s", "http4s-dsl", "1.0.0"))
  }

  /** Creates a `FunFixture` that writes the content to a temporary HOCON file and provides a `PinFinder` loaded from
    * it.
    */
  def withPinFile(contents: String) = FunFixture[PinFinder](
    setup = { _ =>
      val file = Files.createTempFile("pins", ".conf")
      IO.write(file.toFile(), contents)
      PinFinder.fromUrls(List(file.toUri().toURL()))
    },
    teardown = _ => ()
  )

}
