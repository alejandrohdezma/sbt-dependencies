/*
 * Copyright 2025 Alejandro Hernández <https://github.com/alejandrohdezma>
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

import sbt._
import sbt.util.Level
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Dependency.Version
import com.alejandrohdezma.sbt.dependencies.Eq._

class DependenciesFileSuite extends munit.FunSuite {

  implicit val logger: Logger = new Logger {

    override def trace(t: => Throwable): Unit = ()

    override def success(message: => String): Unit = ()

    override def log(level: Level.Value, message: => String): Unit = ()

  }

  // Dummy VersionFinder that always returns 0.1.0
  implicit val dummyVersionFinder: Utils.VersionFinder = (_, _, _, _) =>
    List(Version(List(0, 1, 0), None, Version.Marker.NoMarker))

  def withDependenciesFile(content: String): FunFixture[File] = FunFixture[File](
    setup = { _ =>
      val file = Files.createTempFile("dependencies", ".yaml").toFile
      IO.write(file, content)
      file
    },
    teardown = { file =>
      Files.deleteIfExists(file.toPath)
      ()
    }
  )

  val nonExistentFile: FunFixture[File] = FunFixture[File](
    setup = { _ =>
      val dir  = Files.createTempDirectory("dependencies-test")
      val file = dir.resolve("dependencies.yaml").toFile
      file
    },
    teardown = { file =>
      Files.deleteIfExists(file.toPath)
      Files.deleteIfExists(file.toPath.getParent)
      ()
    }
  )

  withDependenciesFile {
    """|sbt-build:
       |  - ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin
       |  - io.get-coursier::coursier:2.1.24
       |
       |sbt-dependencies:
       |  - org.scalameta::munit:1.2.1:test
       |""".stripMargin
  }.test("read YAML file with multiple groups") { file =>
    val result = DependenciesFile.read(file)

    assertEquals(result.length, 3)
    assertEquals(result.map(_.group).distinct.sorted, List("sbt-build", "sbt-dependencies"))

    val sbtBuildDeps = result.filter(_.group === "sbt-build")
    assertEquals(sbtBuildDeps.length, 2)
    assertEquals(sbtBuildDeps.map(_.name).sorted, List("coursier", "sbt-scalafix"))

    val sbtDepsDeps = result.filter(_.group === "sbt-dependencies")
    assertEquals(sbtDepsDeps.length, 1)
    assertEquals(sbtDepsDeps.head.name, "munit")
    assertEquals(sbtDepsDeps.head.configuration, "test")
  }

  withDependenciesFile("").test("read empty YAML file returns empty list") { file =>
    val result = DependenciesFile.read(file)

    assertEquals(result, List.empty)
  }

  nonExistentFile.test("read non-existent file creates it and returns empty list") { file =>
    assert(!file.exists(), "File should not exist before read")

    val result = DependenciesFile.read(file)

    assertEquals(result, List.empty)
    assert(file.exists(), "File should be created after read")
  }

  withDependenciesFile {
    """|my-project:
       |  - org.typelevel::cats-core:2.10.0
       |""".stripMargin
  }.test("read YAML file with single group") { file =>
    val result = DependenciesFile.read(file)

    assertEquals(result.length, 1)
    assertEquals(result.head.organization, "org.typelevel")
    assertEquals(result.head.name, "cats-core")
    assertEquals(result.head.version.toVersionString, "2.10.0")
    assertEquals(result.head.isCross, true)
    assertEquals(result.head.group, "my-project")
  }

  withDependenciesFile("").test("write dependencies creates properly formatted YAML") { file =>
    val dependencies = List(
      Dependency(
        "org.typelevel",
        "cats-core",
        Version(List(2, 10, 0), None, Version.Marker.NoMarker),
        isCross = true,
        "my-project"
      ),
      Dependency(
        "ch.epfl.scala",
        "sbt-scalafix",
        Version(List(0, 14, 5), None, Version.Marker.NoMarker),
        isCross = false,
        "sbt-build",
        "sbt-plugin"
      ),
      Dependency(
        "io.get-coursier",
        "coursier",
        Version(List(2, 1, 24), None, Version.Marker.NoMarker),
        isCross = true,
        "sbt-build"
      )
    )

    DependenciesFile.write(dependencies, file)

    val content = IO.read(file)

    val expected =
      """|my-project:
         |  - org.typelevel::cats-core:2.10.0
         |
         |sbt-build:
         |  - ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin
         |  - io.get-coursier::coursier:2.1.24
         |""".stripMargin

    assertEquals(content, expected)
  }

  withDependenciesFile("").test("write then read round-trip preserves dependencies") { file =>
    val original = List(
      Dependency(
        "org.typelevel",
        "cats-core",
        Version(List(2, 10, 0), None, Version.Marker.NoMarker),
        isCross = true,
        "project-a"
      ),
      Dependency(
        "com.google.guava",
        "guava",
        Version(List(32, 1, 0), Some("-jre"), Version.Marker.NoMarker),
        isCross = false,
        "project-b"
      ),
      Dependency(
        "org.scalameta",
        "munit",
        Version(List(1, 2, 1), None, Version.Marker.NoMarker),
        isCross = true,
        "project-a",
        "test"
      )
    )

    DependenciesFile.write(original, file)
    val result = DependenciesFile.read(file)

    assertEquals(result.length, original.length)

    original.foreach { dep =>
      val found = result.find(_.isSameArtifact(dep))
      assert(found.isDefined, s"Expected to find ${dep.toLine}")
      assert(found.get.version.isSameVersion(dep.version), s"Version mismatch for ${dep.toLine}")
    }
  }

  withDependenciesFile("").test("write sorts groups alphabetically") { file =>
    val dependencies = List(
      Dependency("org", "z-lib", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "z-group"),
      Dependency("org", "a-lib", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "a-group"),
      Dependency("org", "m-lib", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "m-group")
    )

    DependenciesFile.write(dependencies, file)

    val content    = IO.read(file)
    val groupOrder = content.linesIterator.filter(_.endsWith(":")).map(_.dropRight(1)).toList

    assertEquals(groupOrder, List("a-group", "m-group", "z-group"))
  }

  withDependenciesFile("").test("write sorts dependencies within group alphabetically") { file =>
    val dependencies = List(
      Dependency("org", "z-lib", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "group"),
      Dependency("org", "a-lib", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "group"),
      Dependency("org", "m-lib", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "group")
    )

    DependenciesFile.write(dependencies, file)

    val content  = IO.read(file)
    val depOrder = content.linesIterator.filter(_.startsWith("  - ")).map(_.drop(4)).toList

    assertEquals(depOrder, List("org:a-lib:1.0.0", "org:m-lib:1.0.0", "org:z-lib:1.0.0"))
  }

  withDependenciesFile {
    """|my-project:
       |  - org.typelevel::cats-core:=2.10.0
       |  - org.typelevel::cats-effect:^3.5.0
       |  - org.typelevel::fs2-core:~3.9.0
       |""".stripMargin
  }.test("read preserves version markers") { file =>
    val result = DependenciesFile.read(file)

    val catsCore = result.find(_.name === "cats-core").get
    assertEquals(catsCore.version.marker, Version.Marker.Exact)

    val catsEffect = result.find(_.name === "cats-effect").get
    assertEquals(catsEffect.version.marker, Version.Marker.Major)

    val fs2Core = result.find(_.name === "fs2-core").get
    assertEquals(fs2Core.version.marker, Version.Marker.Minor)
  }

  withDependenciesFile("").test("write preserves version markers") { file =>
    val dependencies = List(
      Dependency(
        "org.typelevel",
        "cats-core",
        Version(List(2, 10, 0), None, Version.Marker.Exact),
        isCross = true,
        "my-project"
      ),
      Dependency(
        "org.typelevel",
        "cats-effect",
        Version(List(3, 5, 0), None, Version.Marker.Major),
        isCross = true,
        "my-project"
      ),
      Dependency(
        "org.typelevel",
        "fs2-core",
        Version(List(3, 9, 0), None, Version.Marker.Minor),
        isCross = true,
        "my-project"
      )
    )

    DependenciesFile.write(dependencies, file)

    val content = IO.read(file)

    assert(content.contains("org.typelevel::cats-core:=2.10.0"), "Expected exact marker (=)")
    assert(content.contains("org.typelevel::cats-effect:^3.5.0"), "Expected major marker (^)")
    assert(content.contains("org.typelevel::fs2-core:~3.9.0"), "Expected minor marker (~)")
  }

  // --- Edge cases ---

  withDependenciesFile("").test("write empty list creates empty file") { file =>
    DependenciesFile.write(List.empty, file)

    val content = IO.read(file)

    assertEquals(content, "\n")
  }

  withDependenciesFile {
    """|# This is a comment
       |my-project:
       |  - org.typelevel::cats-core:2.10.0
       |""".stripMargin
  }.test("read ignores YAML comments") { file =>
    val result = DependenciesFile.read(file)

    assertEquals(result.length, 1)
    assertEquals(result.head.name, "cats-core")
  }

  withDependenciesFile {
    """|my-project:
       |  - org.typelevel::cats-core:2.10.0
       |  - org.typelevel::cats-effect:3.5.0
       |another-project:
       |  - org.scalameta::munit:1.2.1
       |""".stripMargin
  }.test("read handles YAML without blank lines between groups") { file =>
    val result = DependenciesFile.read(file)

    assertEquals(result.length, 3)
    assertEquals(result.filter(_.group === "my-project").length, 2)
    assertEquals(result.filter(_.group === "another-project").length, 1)
  }

  withDependenciesFile {
    """|my-project:
       |- org.typelevel::cats-core:2.10.0
       |""".stripMargin
  }.test("read handles YAML without indentation") { file =>
    val result = DependenciesFile.read(file)

    assertEquals(result.length, 1)
    assertEquals(result.head.name, "cats-core")
  }

}
