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

import scala.concurrent.duration._

import sbt.IO
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.TestLogger
import com.alejandrohdezma.sbt.dependencies.constraints.ConfigCache

class CooldownFinderSuite extends munit.FunSuite {

  implicit val logger: Logger = TestLogger()

  private val tempCacheDir = Files.createTempDirectory("config-cache")

  implicit val configCache: ConfigCache = ConfigCache(tempCacheDir.toFile())

  override def afterAll(): Unit = IO.delete(tempCacheDir.toFile())

  // --- fromUrls tests ---

  withCooldownFile {
    """updates.cooldown = { minimumAge = "7 days" }
      |""".stripMargin
  }.test("default applies to every artifact when no overrides match") { finder =>
    assertEquals(finder.cooldownFor("org.http4s", "http4s-core", "1.0.0"), Some(7.days))
    assertEquals(finder.cooldownFor("org.typelevel", "cats-core", "2.0.0"), Some(7.days))
  }

  withCooldownFile {
    """dependencyOverrides = [
      |  { dependency = { groupId = "com.my-company" }, cooldown = { minimumAge = "0 seconds" } }
      |]
      |""".stripMargin
  }.test("override applies for matching groupId; no default → None for non-matching") { finder =>
    assertEquals(finder.cooldownFor("com.my-company", "platform", "1.0.0"), Some(0.seconds))
    assertEquals(finder.cooldownFor("org.other", "lib", "1.0.0"), None)
  }

  withCooldownFile {
    """updates.cooldown = { minimumAge = "7 days" }
      |
      |dependencyOverrides = [
      |  { dependency = { groupId = "com.my-company" }, cooldown = { minimumAge = "0 seconds" } }
      |]
      |""".stripMargin
  }.test("first matching override wins; default applies otherwise") { finder =>
    assertEquals(finder.cooldownFor("com.my-company", "anything", "1.0.0"), Some(0.seconds))
    assertEquals(finder.cooldownFor("org.other", "lib", "1.0.0"), Some(7.days))
  }

  withCooldownFile {
    """dependencyOverrides = [
      |  { dependency = { groupId = "org.example", artifactId = "lib", version = { prefix = "1." } },
      |    cooldown   = { minimumAge = "30 days" } }
      |  { dependency = { groupId = "org.example" },
      |    cooldown   = { minimumAge = "1 day" } }
      |]
      |""".stripMargin
  }.test("more-specific override placed first wins; broader override matches other versions") { finder =>
    assertEquals(finder.cooldownFor("org.example", "lib", "1.5.0"), Some(30.days))
    assertEquals(finder.cooldownFor("org.example", "lib", "2.0.0"), Some(1.day))
    assertEquals(finder.cooldownFor("org.example", "other-lib", "1.0.0"), Some(1.day))
  }

  test("fromUrls with empty URL list returns None for everything") {
    val finder = CooldownFinder.fromUrls(Nil)

    assertEquals(finder.cooldownFor("org.example", "lib", "1.0.0"), None)
  }

  test("empty finder returns None for everything") {
    assertEquals(CooldownFinder.empty.cooldownFor("anything", "anything", "anything"), None)
  }

  /** Creates a `FunFixture` that writes the content to a temporary HOCON file and provides a `CooldownFinder` loaded
    * from it.
    */
  def withCooldownFile(contents: String) = FunFixture[CooldownFinder](
    setup = { _ =>
      val file = Files.createTempFile("cooldown", ".conf")
      IO.write(file.toFile(), contents)
      CooldownFinder.fromUrls(List(file.toUri()))
    },
    teardown = _ => ()
  )

}
