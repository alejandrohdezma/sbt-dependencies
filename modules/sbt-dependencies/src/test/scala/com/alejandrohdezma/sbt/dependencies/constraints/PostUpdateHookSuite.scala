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

import com.alejandrohdezma.sbt.dependencies.TestLogger

class PostUpdateHookSuite extends munit.FunSuite {

  implicit val logger: TestLogger = TestLogger()

  private val tempCacheDir = Files.createTempDirectory("config-cache")

  implicit val configCache: ConfigCache = ConfigCache(tempCacheDir.toFile())

  override def afterAll(): Unit = IO.delete(tempCacheDir.toFile())

  override def beforeEach(context: BeforeEach): Unit = logger.cleanLogs()

  withHookFile {
    """postUpdateHooks = [
      |  {
      |    groupId = "com.github.liancheng"
      |    artifactId = "organize-imports"
      |    command = ["sbt", "scalafixAll"]
      |    commitMessage = "Reorganize imports with OrganizeImports ${nextVersion}"
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls parses entry with all fields") { urls =>
    val hooks = PostUpdateHook.loadFromUrls(urls)

    val expected = PostUpdateHook(
      groupId = Some("com.github.liancheng"),
      artifactId = Some("organize-imports"),
      command = List("sbt", "scalafixAll"),
      commitMessage = "Reorganize imports with OrganizeImports ${nextVersion}"
    )

    assertEquals(hooks, List(expected))
  }

  withHookFile {
    """postUpdateHooks = [
      |  {
      |    command = ["sbt", "compile"]
      |    commitMessage = "Recompile"
      |  }
      |]
      |""".stripMargin
  }.test("loadFromUrls handles optional groupId and artifactId") { urls =>
    val hooks = PostUpdateHook.loadFromUrls(urls)

    val expected = List(
      PostUpdateHook(groupId = None, artifactId = None, command = List("sbt", "compile"), commitMessage = "Recompile")
    )

    assertEquals(hooks, expected)
  }

  withHookFile {
    """updates.pin = [
      |  { groupId = "org.scala-lang" }
      |]
      |""".stripMargin
  }.test("loadFromUrls skips files without postUpdateHooks path") { urls =>
    val hooks = PostUpdateHook.loadFromUrls(urls)

    assertEquals(hooks, Nil)
  }

  def withHookFile(contents: String*) = FunFixture[List[URL]](
    setup = { _ =>
      contents.toList.map { content =>
        val file = Files.createTempFile("hooks", ".conf")
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
