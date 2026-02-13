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

class IgnoreFinderSuite extends munit.FunSuite {

  implicit val logger: Logger = TestLogger()

  // --- fromUrls tests ---

  withIgnoreFile {
    """updates.ignore = [
      |  { groupId = "org.scala-lang", artifactId = "scala3-compiler", version = { exact = "3.8.2" } }
      |]
      |""".stripMargin
  }.test("fromUrls ignores matching version") { finder =>
    assert(finder.isIgnored("org.scala-lang", "scala3-compiler", "3.8.2"))
  }

  withIgnoreFile {
    """updates.ignore = [
      |  { groupId = "org.scala-lang", artifactId = "scala3-compiler", version = { exact = "3.8.2" } }
      |]
      |""".stripMargin
  }.test("fromUrls does not ignore non-matching version") { finder =>
    assert(!finder.isIgnored("org.scala-lang", "scala3-compiler", "3.8.1"))
  }

  withIgnoreFile {
    """updates.ignore = [
      |  { groupId = "org.scala-lang", artifactId = "scala3-compiler", version = { exact = "3.8.2" } }
      |]
      |""".stripMargin
  }.test("fromUrls does not ignore non-matching dependency") { finder =>
    assert(!finder.isIgnored("org.typelevel", "cats-core", "2.0.0"))
  }

  withIgnoreFile {
    """updates.ignore = [
      |  { groupId = "com.typesafe.akka" }
      |]
      |""".stripMargin
  }.test("fromUrls ignores all artifacts and versions for groupId-only entry") { finder =>
    assert(finder.isIgnored("com.typesafe.akka", "akka-actor", "2.6.0"))
    assert(finder.isIgnored("com.typesafe.akka", "akka-stream", "2.7.0"))
  }

  test("fromUrls with empty URL list returns finder that never ignores") {
    val finder = IgnoreFinder.fromUrls(Nil)

    assert(!finder.isIgnored("org.scala-lang", "scala3-compiler", "3.8.2"))
  }

  test("empty finder never ignores") {
    assert(!IgnoreFinder.empty.isIgnored("org.scala-lang", "scala3-compiler", "3.8.2"))
  }

  /** Creates a `FunFixture` that writes the content to a temporary HOCON file and provides an `IgnoreFinder` loaded
    * from it.
    */
  def withIgnoreFile(contents: String) = FunFixture[IgnoreFinder](
    setup = { _ =>
      val file = Files.createTempFile("ignores", ".conf")
      IO.write(file.toFile(), contents)
      IgnoreFinder.fromUrls(List(file.toUri().toURL()))
    },
    teardown = _ => ()
  )

}
