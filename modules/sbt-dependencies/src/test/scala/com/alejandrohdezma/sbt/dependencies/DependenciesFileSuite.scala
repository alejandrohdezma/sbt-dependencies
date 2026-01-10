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

import sbt._
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
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

  val variableResolvers: Map[String, OrganizationArtifactName => ModuleID] =
    Map.empty

  // Dummy VersionFinder that always returns 0.1.0
  implicit val dummyVersionFinder: Utils.VersionFinder = (_, _, _, _) =>
    List(Version.Numeric(List(0, 1, 0), None, Version.Numeric.Marker.NoMarker))

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
  }.test("read YAML file returns only specified group") { file =>
    val sbtBuildDeps = DependenciesFile.read(file, "sbt-build", variableResolvers)
    assertEquals(sbtBuildDeps.length, 2)
    assertEquals(sbtBuildDeps.map(_.name).sorted, List("coursier", "sbt-scalafix"))

    val sbtDepsDeps = DependenciesFile.read(file, "sbt-dependencies", variableResolvers)
    assertEquals(sbtDepsDeps.length, 1)
    assertEquals(sbtDepsDeps.head.name, "munit")
    assertEquals(sbtDepsDeps.head.configuration, "test")
  }

  withDependenciesFile("").test("read empty YAML file returns empty list") { file =>
    val result = DependenciesFile.read(file, "any-group", variableResolvers)

    assertEquals(result, List.empty)
  }

  nonExistentFile.test("read non-existent file returns empty list without creating file") { file =>
    assert(!file.exists(), "File should not exist before read")

    val result = DependenciesFile.read(file, "any-group", variableResolvers)

    assertEquals(result, List.empty)
    assert(!file.exists(), "File should not be created by read")
  }

  withDependenciesFile {
    """|my-project:
       |  - org.typelevel::cats-core:2.10.0
       |""".stripMargin
  }.test("read YAML file with single group") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    assertEquals(result.length, 1)
    assertEquals(result.head.organization, "org.typelevel")
    assertEquals(result.head.name, "cats-core")
    assertEquals(result.head.version.toVersionString, "2.10.0")
    assertEquals(result.head.isCross, true)
    assertEquals(result.head.group, "my-project")
  }

  withDependenciesFile {
    """|my-project:
       |  - org.typelevel::cats-core:2.10.0
       |""".stripMargin
  }.test("read YAML file with non-existent group returns empty list") { file =>
    val result = DependenciesFile.read(file, "other-project", variableResolvers)

    assertEquals(result, List.empty)
  }

  withDependenciesFile("").test("write dependencies creates properly formatted YAML") { file =>
    val myProjectDeps = List(
      Dependency(
        "org.typelevel",
        "cats-core",
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "my-project"
      )
    )

    val sbtBuildDeps = List(
      Dependency(
        "ch.epfl.scala",
        "sbt-scalafix",
        Version.Numeric(List(0, 14, 5), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "sbt-build",
        "sbt-plugin"
      ),
      Dependency(
        "io.get-coursier",
        "coursier",
        Version.Numeric(List(2, 1, 24), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "sbt-build"
      )
    )

    DependenciesFile.write(file, "my-project", myProjectDeps)
    DependenciesFile.write(file, "sbt-build", sbtBuildDeps)

    val content = IO.read(file)

    val expected =
      """|my-project:
         |  - org.typelevel::cats-core:2.10.0
         |
         |sbt-build:
         |  - io.get-coursier::coursier:2.1.24
         |  - ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin
         |""".stripMargin

    assertEquals(content, expected)
  }

  withDependenciesFile("").test("write sorts dependencies by configuration then alphabetically") { file =>
    val dependencies = List(
      Dependency(
        "org",
        "z-lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "group",
        "test"
      ),
      Dependency(
        "org",
        "a-lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "group",
        "compile"
      ),
      Dependency(
        "org",
        "m-lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "group",
        "test"
      ),
      Dependency(
        "org",
        "b-lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "group",
        "compile"
      )
    )

    DependenciesFile.write(file, "group", dependencies)

    val content  = IO.read(file)
    val depOrder = content.linesIterator.filter(_.startsWith("  - ")).map(_.drop(4)).toList

    // compile deps first (a-lib, b-lib), then test deps (m-lib, z-lib)
    assertEquals(depOrder, List("org:a-lib:1.0.0", "org:b-lib:1.0.0", "org:m-lib:1.0.0:test", "org:z-lib:1.0.0:test"))
  }

  withDependenciesFile("").test("write then read round-trip preserves dependencies") { file =>
    val projectADeps = List(
      Dependency(
        "org.typelevel",
        "cats-core",
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "project-a"
      ),
      Dependency(
        "org.scalameta",
        "munit",
        Version.Numeric(List(1, 2, 1), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "project-a",
        "test"
      )
    )

    val projectBDeps = List(
      Dependency(
        "com.google.guava",
        "guava",
        Version.Numeric(List(32, 1, 0), Some("-jre"), Version.Numeric.Marker.NoMarker),
        isCross = false,
        "project-b"
      )
    )

    DependenciesFile.write(file, "project-a", projectADeps)
    DependenciesFile.write(file, "project-b", projectBDeps)

    val resultA = DependenciesFile.read(file, "project-a", variableResolvers)
    val resultB = DependenciesFile.read(file, "project-b", variableResolvers)

    assertEquals(resultA.length, projectADeps.length)
    assertEquals(resultB.length, projectBDeps.length)

    projectADeps.foreach { dep =>
      val found = resultA.find(_.isSameArtifact(dep))
      assert(found.isDefined, s"Expected to find ${dep.toLine}")
      (found.get.version, dep.version) match {
        case (v1: Version.Numeric, v2: Version.Numeric) =>
          assert(v1.isSameVersion(v2), s"Version mismatch for ${dep.toLine}")
        case _ =>
          fail(s"Expected Numeric versions for ${dep.toLine}")
      }
    }
  }

  withDependenciesFile("").test("write sorts groups alphabetically") { file =>
    // Write groups in reverse alphabetical order
    DependenciesFile.write(
      file,
      "z-group",
      List(
        Dependency(
          "org",
          "z-lib",
          Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
          false,
          "z-group"
        )
      )
    )
    DependenciesFile.write(
      file,
      "a-group",
      List(
        Dependency(
          "org",
          "a-lib",
          Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
          false,
          "a-group"
        )
      )
    )
    DependenciesFile.write(
      file,
      "m-group",
      List(
        Dependency(
          "org",
          "m-lib",
          Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
          false,
          "m-group"
        )
      )
    )

    val content    = IO.read(file)
    val groupOrder = content.linesIterator.filter(_.endsWith(":")).map(_.dropRight(1)).toList

    assertEquals(groupOrder, List("a-group", "m-group", "z-group"))
  }

  withDependenciesFile("").test("write sorts dependencies within group alphabetically") { file =>
    val dependencies = List(
      Dependency("org", "z-lib", Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker), false, "group"),
      Dependency("org", "a-lib", Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker), false, "group"),
      Dependency("org", "m-lib", Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker), false, "group")
    )

    DependenciesFile.write(file, "group", dependencies)

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
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    val catsCore = result.find(_.name === "cats-core").get
    catsCore.version match {
      case v: Version.Numeric => assertEquals(v.marker, Version.Numeric.Marker.Exact)
      case _                  => fail("Expected Numeric version")
    }

    val catsEffect = result.find(_.name === "cats-effect").get
    catsEffect.version match {
      case v: Version.Numeric => assertEquals(v.marker, Version.Numeric.Marker.Major)
      case _                  => fail("Expected Numeric version")
    }

    val fs2Core = result.find(_.name === "fs2-core").get
    fs2Core.version match {
      case v: Version.Numeric => assertEquals(v.marker, Version.Numeric.Marker.Minor)
      case _                  => fail("Expected Numeric version")
    }
  }

  withDependenciesFile("").test("write preserves version markers") { file =>
    val dependencies = List(
      Dependency(
        "org.typelevel",
        "cats-core",
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.Exact),
        isCross = true,
        "my-project"
      ),
      Dependency(
        "org.typelevel",
        "cats-effect",
        Version.Numeric(List(3, 5, 0), None, Version.Numeric.Marker.Major),
        isCross = true,
        "my-project"
      ),
      Dependency(
        "org.typelevel",
        "fs2-core",
        Version.Numeric(List(3, 9, 0), None, Version.Numeric.Marker.Minor),
        isCross = true,
        "my-project"
      )
    )

    DependenciesFile.write(file, "my-project", dependencies)

    val content = IO.read(file)

    assert(content.contains("org.typelevel::cats-core:=2.10.0"), "Expected exact marker (=)")
    assert(content.contains("org.typelevel::cats-effect:^3.5.0"), "Expected major marker (^)")
    assert(content.contains("org.typelevel::fs2-core:~3.9.0"), "Expected minor marker (~)")
  }

  // --- Edge cases ---

  withDependenciesFile("").test("write empty list does nothing") { file =>
    DependenciesFile.write(file, "empty-group", List.empty)

    val content = IO.read(file)

    assertEquals(content, "")
  }

  withDependenciesFile {
    """|# This is a comment
       |my-project:
       |  - org.typelevel::cats-core:2.10.0
       |""".stripMargin
  }.test("read ignores YAML comments") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

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
    val myProjectDeps   = DependenciesFile.read(file, "my-project", variableResolvers)
    val anotherProjDeps = DependenciesFile.read(file, "another-project", variableResolvers)

    assertEquals(myProjectDeps.length, 2)
    assertEquals(anotherProjDeps.length, 1)
  }

  withDependenciesFile {
    """|my-project:
       |- org.typelevel::cats-core:2.10.0
       |""".stripMargin
  }.test("read handles YAML without indentation") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    assertEquals(result.length, 1)
    assertEquals(result.head.name, "cats-core")
  }

  withDependenciesFile {
    """|existing-group:
       |  - org.typelevel::cats-core:2.10.0
       |""".stripMargin
  }.test("write preserves other groups") { file =>
    val newDeps = List(
      Dependency(
        "org.scalameta",
        "munit",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        true,
        "new-group",
        "test"
      )
    )

    DependenciesFile.write(file, "new-group", newDeps)

    val content = IO.read(file)

    assert(content.contains("existing-group:"), "Expected existing group to be preserved")
    assert(content.contains("org.typelevel::cats-core:2.10.0"), "Expected existing dependency to be preserved")
    assert(content.contains("new-group:"), "Expected new group to be added")
    assert(content.contains("org.scalameta::munit:1.0.0:test"), "Expected new dependency to be added")
  }

  withDependenciesFile("").test("write removes duplicate dependencies") { file =>
    val dependencies = List(
      Dependency(
        "org",
        "lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "group"
      ),
      Dependency(
        "org",
        "lib",
        Version.Numeric(List(2, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "group"
      ), // duplicate artifact, different version
      Dependency(
        "org",
        "other",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "group"
      )
    )

    DependenciesFile.write(file, "group", dependencies)

    val content  = IO.read(file)
    val depCount = content.linesIterator.count(_.startsWith("  - "))

    assertEquals(depCount, 2) // Only 2 unique artifacts
  }

  // --- Advanced format tests ---

  withDependenciesFile {
    """|my-project:
       |  dependencies:
       |    - org.typelevel::cats-core:2.10.0
       |    - org.scalameta::munit:1.2.1:test
       |""".stripMargin
  }.test("read advanced format YAML file") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    assertEquals(result.length, 2)
    assertEquals(result.map(_.name).sorted, List("cats-core", "munit"))
  }

  withDependenciesFile {
    """|simple-project:
       |  - org.typelevel::cats-core:2.10.0
       |
       |advanced-project:
       |  dependencies:
       |    - org.scalameta::munit:1.2.1:test
       |""".stripMargin
  }.test("read mixed format YAML file") { file =>
    val simple   = DependenciesFile.read(file, "simple-project", variableResolvers)
    val advanced = DependenciesFile.read(file, "advanced-project", variableResolvers)

    assertEquals(simple.length, 1)
    assertEquals(simple.head.name, "cats-core")
    assertEquals(advanced.length, 1)
    assertEquals(advanced.head.name, "munit")
  }

  withDependenciesFile {
    """|my-project:
       |  dependencies: []
       |""".stripMargin
  }.test("read advanced format with empty dependencies") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    assertEquals(result, List.empty)
  }

  withDependenciesFile {
    """|my-project:
       |  - org.typelevel::cats-core:2.10.0
       |""".stripMargin
  }.test("write preserves simple format") { file =>
    val newDeps = List(
      Dependency(
        "org.typelevel",
        "cats-effect",
        Version.Numeric(List(3, 5, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "my-project"
      )
    )

    DependenciesFile.write(file, "my-project", newDeps)

    val content = IO.read(file)

    assert(!content.contains("  dependencies:"), "Should preserve simple format")
    assert(content.contains("  - org.typelevel::cats-effect:3.5.0"))
  }

  withDependenciesFile {
    """|my-project:
       |  dependencies:
       |    - org.typelevel::cats-core:2.10.0
       |""".stripMargin
  }.test("write preserves advanced format") { file =>
    val newDeps = List(
      Dependency(
        "org.typelevel",
        "cats-effect",
        Version.Numeric(List(3, 5, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "my-project"
      )
    )

    DependenciesFile.write(file, "my-project", newDeps)

    val content = IO.read(file)

    assert(content.contains("  dependencies:"), "Should preserve advanced format")
    assert(content.contains("    - org.typelevel::cats-effect:3.5.0"))
  }

  withDependenciesFile {
    """|simple-project:
       |  - org.typelevel::cats-core:2.10.0
       |
       |advanced-project:
       |  dependencies:
       |    - org.scalameta::munit:1.2.1:test
       |""".stripMargin
  }.test("write preserves format in mixed file") { file =>
    val simpleDeps = List(
      Dependency(
        "org.typelevel",
        "cats-effect",
        Version.Numeric(List(3, 5, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "simple-project"
      )
    )
    val advancedDeps = List(
      Dependency(
        "org.scalatest",
        "scalatest",
        Version.Numeric(List(3, 2, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "advanced-project",
        "test"
      )
    )

    DependenciesFile.write(file, "simple-project", simpleDeps)
    DependenciesFile.write(file, "advanced-project", advancedDeps)

    val content = IO.read(file)

    // Simple project should NOT have nested dependencies key
    val simpleSection = content.split("\n\n").find(_.startsWith("simple-project:")).get
    assert(!simpleSection.contains("dependencies:"), "Simple should stay simple")

    // Advanced project SHOULD have nested dependencies key
    val advancedSection = content.split("\n\n").find(_.startsWith("advanced-project:")).get
    assert(advancedSection.contains("  dependencies:"), "Advanced should stay advanced")
  }

  withDependenciesFile("").test("write then read round-trip preserves advanced format") { file =>
    val deps = List(
      Dependency(
        "org.typelevel",
        "cats-core",
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "my-project"
      )
    )

    // First write in simple format (default)
    DependenciesFile.write(file, "my-project", deps)

    // Now manually create an advanced format file
    IO.write(
      file,
      """|my-project:
         |  dependencies:
         |    - org.typelevel::cats-core:2.10.0
         |""".stripMargin
    )

    // Write again - should preserve advanced format
    DependenciesFile.write(file, "my-project", deps)

    val content = IO.read(file)
    assert(content.contains("  dependencies:"), "Should preserve advanced format after write")

    val result = DependenciesFile.read(file, "my-project", variableResolvers)
    assertEquals(result.length, 1)
    assertEquals(result.head.name, "cats-core")
  }

  withDependenciesFile("").test("write new group uses simple format") { file =>
    val deps = List(
      Dependency(
        "org.typelevel",
        "cats-core",
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "new-project"
      )
    )

    DependenciesFile.write(file, "new-project", deps)

    val content = IO.read(file)
    assert(!content.contains("  dependencies:"), "New groups should use simple format")
  }

  withDependenciesFile {
    """|my-project:
       |  dependencies:
       |    - org.typelevel::cats-core:2.10.0
       |  unknownField: someValue
       |""".stripMargin
  }.test("read ignores unknown fields in advanced format") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    assertEquals(result.length, 1)
    assertEquals(result.head.name, "cats-core")
  }

  withDependenciesFile {
    """|my-project:
       |  dependencies:
       |    - org.typelevel::cats-core:=2.10.0
       |    - org.typelevel::cats-effect:^3.5.0
       |""".stripMargin
  }.test("read advanced format preserves version markers") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    val catsCore = result.find(_.name === "cats-core").get
    catsCore.version match {
      case v: Version.Numeric => assertEquals(v.marker, Version.Numeric.Marker.Exact)
      case _                  => fail("Expected Numeric version")
    }

    val catsEffect = result.find(_.name === "cats-effect").get
    catsEffect.version match {
      case v: Version.Numeric => assertEquals(v.marker, Version.Numeric.Marker.Major)
      case _                  => fail("Expected Numeric version")
    }
  }

}
