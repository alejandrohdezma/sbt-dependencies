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
import scala.concurrent.duration._

import sbt.IO
import sbt.util.Level

import com.alejandrohdezma.sbt.dependencies.TestLogger

class CooldownSuite extends munit.FunSuite {

  implicit val logger: TestLogger = TestLogger()

  private val tempCacheDir = Files.createTempDirectory("config-cache")

  implicit val configCache: ConfigCache = ConfigCache(tempCacheDir.toFile())

  override def afterAll(): Unit = IO.delete(tempCacheDir.toFile())

  override def beforeEach(context: BeforeEach): Unit = logger.cleanLogs()

  // --- HOCON parsing tests ---

  withCooldownFile {
    """updates.cooldown = { minimumAge = "7 days" }
      |""".stripMargin
  }.test("loadFromUrls parses the default cooldown") { urls =>
    val entries = CooldownEntry.loadFromUrls(urls)

    assertEquals(entries, List(CooldownDefault(7.days)))
  }

  withCooldownFile {
    """dependencyOverrides = [
      |  { dependency = { groupId = "com.my-company" }, cooldown = { minimumAge = "0 seconds" } }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses an override with groupId only") { urls =>
    val entries = CooldownEntry.loadFromUrls(urls)

    assertEquals(entries, List(CooldownOverride("com.my-company", None, None, 0.seconds)))
  }

  withCooldownFile {
    """updates.cooldown = { minimumAge = "7 days" }
      |
      |dependencyOverrides = [
      |  { dependency = { groupId = "com.my-company" },
      |    cooldown   = { minimumAge = "0 seconds" } }
      |  { dependency = { groupId = "org.example", artifactId = "lib", version = { prefix = "1." } },
      |    cooldown   = { minimumAge = "30 days" } }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses default + multiple overrides in order") { urls =>
    val entries = CooldownEntry.loadFromUrls(urls)

    val expected = List(
      CooldownDefault(7.days),
      CooldownOverride("com.my-company", None, None, 0.seconds),
      CooldownOverride("org.example", Some("lib"), Some(VersionPattern(prefix = Some("1."))), 30.days)
    )

    assertEquals(entries, expected)
  }

  withCooldownFile {
    """dependencyOverrides = [
      |  { dependency = { groupId = "com.my-company" }, pullRequests = { frequency = "1 day" } }
      |]
      |""".stripMargin
  }.test("loadFromUrls skips overrides that don't carry a cooldown block") { urls =>
    val entries = CooldownEntry.loadFromUrls(urls)

    assertEquals(entries, Nil)
  }

  withCooldownFile {
    """updates.cooldown = { minimumAge = "not a duration" }
      |""".stripMargin
  }.test("loadFromUrls warns and skips file with malformed duration") { urls =>
    val entries = CooldownEntry.loadFromUrls(urls)

    assertEquals(entries, Nil)

    assert(
      logger.getLogs(Level.Warn).exists { msg =>
        msg.startsWith(s"⚠ Skipping malformed ${CooldownEntry.name} from $CYAN${urls.head}$RESET") &&
        msg.contains("not a valid duration")
      },
      logger.getLogs(Level.Warn).mkString("\n")
    )
  }

  withCooldownFile {
    """dependencyOverrides = [
      |  { cooldown = { minimumAge = "1 day" } }
      |]
      |""".stripMargin
  }.test("loadFromUrls warns when override is missing dependency.groupId") { urls =>
    val entries = CooldownEntry.loadFromUrls(urls)

    assertEquals(entries, Nil)

    val expectedLogs = List(
      s"⚠ Skipping malformed ${CooldownEntry.name} from $CYAN${urls.head}$RESET: entry at index 0: " +
        "must have a 'dependency.groupId'"
    )

    assertEquals(logger.getLogs(Level.Warn), expectedLogs)
  }

  test("loadFromUrls returns empty list for empty URL list") {
    val result = CooldownEntry.loadFromUrls(Nil)

    assertEquals(result, Nil)
  }

  test("default URL list is empty (cooldown is per-repo policy, not an ecosystem fact)") {
    assertEquals(CooldownEntry.default, Nil)
  }

  // --- CooldownOverride.matches tests ---

  test("CooldownOverride matches by groupId only") {
    val o = CooldownOverride("com.my-company", None, None, 0.seconds)

    assert(o.matches("com.my-company", "anything", "1.0.0"))
    assert(!o.matches("org.other", "anything", "1.0.0"))
  }

  test("CooldownOverride matches by groupId + artifactId") {
    val o = CooldownOverride("org.example", Some("lib"), None, 1.day)

    assert(o.matches("org.example", "lib", "1.0.0"))
    assert(!o.matches("org.example", "other-lib", "1.0.0"))
  }

  test("CooldownOverride matches by groupId + artifactId + version") {
    val o = CooldownOverride("org.example", Some("lib"), Some(VersionPattern(prefix = Some("1."))), 30.days)

    assert(o.matches("org.example", "lib", "1.0.0"))
    assert(o.matches("org.example", "lib", "1.5.2"))
    assert(!o.matches("org.example", "lib", "2.0.0"))
  }

  //////////////
  // Fixtures //
  //////////////

  def withCooldownFile(contents: String*) = FunFixture[List[URL]](
    setup = { _ =>
      contents.toList.map { content =>
        val file = Files.createTempFile("cooldown", ".conf")
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
