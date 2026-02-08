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
import com.alejandrohdezma.sbt.dependencies.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.Eq._

class DependenciesFileSuite extends munit.FunSuite {

  implicit val logger: Logger = new Logger {

    override def trace(t: => Throwable): Unit = ()

    override def success(message: => String): Unit = ()

    override def log(level: Level.Value, message: => String): Unit = ()

  }

  // Helper to parse a version string into Numeric with Minor marker (matching readScalaVersions behavior)
  def v(version: String): Numeric =
    Version.Numeric.unapply(version).get.copy(marker = Numeric.Marker.Minor)

  val variableResolvers: Map[String, OrganizationArtifactName => ModuleID] =
    Map.empty

  // Dummy VersionFinder that always returns 0.1.0
  implicit val dummyVersionFinder: Utils.VersionFinder = (_, _, _, _) =>
    List(Version.Numeric(List(0, 1, 0), None, Version.Numeric.Marker.NoMarker))

  def withDependenciesFile(content: String): FunFixture[File] = FunFixture[File](
    setup = { _ =>
      val file = Files.createTempFile("dependencies", ".conf").toFile
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
      val file = dir.resolve("dependencies.conf").toFile
      file
    },
    teardown = { file =>
      Files.deleteIfExists(file.toPath)
      Files.deleteIfExists(file.toPath.getParent)
      ()
    }
  )

  withDependenciesFile {
    """|sbt-build = [
       |  "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"
       |  "io.get-coursier::coursier:2.1.24"
       |]
       |
       |sbt-dependencies = [
       |  "org.scalameta::munit:1.2.1:test"
       |]
       |""".stripMargin
  }.test("read HOCON file returns only specified group") { file =>
    val sbtBuildDeps = DependenciesFile.read(file, "sbt-build", variableResolvers)
    assertEquals(sbtBuildDeps.length, 2)
    assertEquals(sbtBuildDeps.map(_.name).sorted, List("coursier", "sbt-scalafix"))

    val sbtDepsDeps = DependenciesFile.read(file, "sbt-dependencies", variableResolvers)
    assertEquals(sbtDepsDeps.length, 1)
    assertEquals(sbtDepsDeps.head.name, "munit")
    assertEquals(sbtDepsDeps.head.configuration, "test")
  }

  withDependenciesFile("").test("read empty HOCON file returns empty list") { file =>
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
    """|my-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |""".stripMargin
  }.test("read HOCON file with single group") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    assertEquals(result.length, 1)
    assertEquals(result.head.organization, "org.typelevel")
    assertEquals(result.head.name, "cats-core")
    assertEquals(result.head.version.toVersionString, "2.10.0")
    assertEquals(result.head.isCross, true)
  }

  withDependenciesFile {
    """|my-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |""".stripMargin
  }.test("read HOCON file with non-existent group returns empty list") { file =>
    val result = DependenciesFile.read(file, "other-project", variableResolvers)

    assertEquals(result, List.empty)
  }

  withDependenciesFile("").test("write dependencies creates properly formatted HOCON") { file =>
    val myProjectDeps = List(
      Dependency.WithNumericVersion(
        "org.typelevel",
        "cats-core",
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )
    )

    val sbtBuildDeps = List(
      Dependency.WithNumericVersion(
        "ch.epfl.scala",
        "sbt-scalafix",
        Version.Numeric(List(0, 14, 5), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "sbt-plugin"
      ),
      Dependency.WithNumericVersion(
        "io.get-coursier",
        "coursier",
        Version.Numeric(List(2, 1, 24), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )
    )

    DependenciesFile.write(file, "my-project", myProjectDeps)
    DependenciesFile.write(file, "sbt-build", sbtBuildDeps)

    val content = IO.read(file)

    val expected =
      """|my-project = [
         |  "org.typelevel::cats-core:2.10.0"
         |]
         |
         |sbt-build = [
         |  "io.get-coursier::coursier:2.1.24"
         |  "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"
         |]
         |""".stripMargin

    assertEquals(content, expected)
  }

  withDependenciesFile("").test("write sorts dependencies by configuration then alphabetically") { file =>
    val dependencies = List(
      Dependency.WithNumericVersion(
        "org",
        "z-lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "test"
      ),
      Dependency.WithNumericVersion(
        "org",
        "a-lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "compile"
      ),
      Dependency.WithNumericVersion(
        "org",
        "m-lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "test"
      ),
      Dependency.WithNumericVersion(
        "org",
        "b-lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false,
        "compile"
      )
    )

    DependenciesFile.write(file, "group", dependencies)

    val content = IO.read(file)
    val depOrder =
      content.linesIterator.filter(_.trim.startsWith("\"")).map(_.trim.stripPrefix("\"").stripSuffix("\"")).toList

    // compile deps first (a-lib, b-lib), then test deps (m-lib, z-lib)
    assertEquals(depOrder, List("org:a-lib:1.0.0", "org:b-lib:1.0.0", "org:m-lib:1.0.0:test", "org:z-lib:1.0.0:test"))
  }

  withDependenciesFile("").test("write then read round-trip preserves dependencies") { file =>
    val projectADeps = List(
      Dependency.WithNumericVersion(
        "org.typelevel",
        "cats-core",
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      ),
      Dependency.WithNumericVersion(
        "org.scalameta",
        "munit",
        Version.Numeric(List(1, 2, 1), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "test"
      )
    )

    val projectBDeps = List(
      Dependency.WithNumericVersion(
        "com.google.guava",
        "guava",
        Version.Numeric(List(32, 1, 0), Some("-jre"), Version.Numeric.Marker.NoMarker),
        isCross = false
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
        Dependency.WithNumericVersion(
          "org",
          "z-lib",
          Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
          false
        )
      )
    )
    DependenciesFile.write(
      file,
      "a-group",
      List(
        Dependency.WithNumericVersion(
          "org",
          "a-lib",
          Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
          false
        )
      )
    )
    DependenciesFile.write(
      file,
      "m-group",
      List(
        Dependency.WithNumericVersion(
          "org",
          "m-lib",
          Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
          false
        )
      )
    )

    val content    = IO.read(file)
    val groupOrder = content.linesIterator.filter(_.contains(" = [")).map(_.split(" = ")(0)).toList

    assertEquals(groupOrder, List("a-group", "m-group", "z-group"))
  }

  withDependenciesFile("").test("write sorts dependencies within group alphabetically") { file =>
    val dependencies = List(
      Dependency.WithNumericVersion(
        "org",
        "z-lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        false
      ),
      Dependency.WithNumericVersion(
        "org",
        "a-lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        false
      ),
      Dependency.WithNumericVersion(
        "org",
        "m-lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        false
      )
    )

    DependenciesFile.write(file, "group", dependencies)

    val content = IO.read(file)
    val depOrder =
      content.linesIterator.filter(_.trim.startsWith("\"")).map(_.trim.stripPrefix("\"").stripSuffix("\"")).toList

    assertEquals(depOrder, List("org:a-lib:1.0.0", "org:m-lib:1.0.0", "org:z-lib:1.0.0"))
  }

  withDependenciesFile {
    """|my-project = [
       |  "org.typelevel::cats-core:=2.10.0"
       |  "org.typelevel::cats-effect:^3.5.0"
       |  "org.typelevel::fs2-core:~3.9.0"
       |]
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
      Dependency.WithNumericVersion(
        "org.typelevel",
        "cats-core",
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.Exact),
        isCross = true
      ),
      Dependency.WithNumericVersion(
        "org.typelevel",
        "cats-effect",
        Version.Numeric(List(3, 5, 0), None, Version.Numeric.Marker.Major),
        isCross = true
      ),
      Dependency.WithNumericVersion(
        "org.typelevel",
        "fs2-core",
        Version.Numeric(List(3, 9, 0), None, Version.Numeric.Marker.Minor),
        isCross = true
      )
    )

    DependenciesFile.write(file, "my-project", dependencies)

    val content = IO.read(file)

    val expected =
      """|my-project = [
         |  "org.typelevel::cats-core:=2.10.0"
         |  "org.typelevel::cats-effect:^3.5.0"
         |  "org.typelevel::fs2-core:~3.9.0"
         |]
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  // --- Edge cases ---

  withDependenciesFile("").test("write empty list does nothing") { file =>
    DependenciesFile.write(file, "empty-group", List.empty)

    val content = IO.read(file)

    assertEquals(content, "")
  }

  withDependenciesFile {
    """|# This is a comment
       |my-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |""".stripMargin
  }.test("read ignores HOCON comments") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    assertEquals(result.length, 1)
    assertEquals(result.head.name, "cats-core")
  }

  withDependenciesFile {
    """|my-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |  "org.typelevel::cats-effect:3.5.0"
       |]
       |another-project = [
       |  "org.scalameta::munit:1.2.1"
       |]
       |""".stripMargin
  }.test("read handles HOCON without blank lines between groups") { file =>
    val myProjectDeps   = DependenciesFile.read(file, "my-project", variableResolvers)
    val anotherProjDeps = DependenciesFile.read(file, "another-project", variableResolvers)

    assertEquals(myProjectDeps.length, 2)
    assertEquals(anotherProjDeps.length, 1)
  }

  withDependenciesFile {
    """|existing-group = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |""".stripMargin
  }.test("write preserves other groups") { file =>
    val newDeps = List(
      Dependency.WithNumericVersion(
        "org.scalameta",
        "munit",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        true,
        "test"
      )
    )

    DependenciesFile.write(file, "new-group", newDeps)

    val content = IO.read(file)

    val expected =
      """|existing-group = [
         |  "org.typelevel::cats-core:2.10.0"
         |]
         |
         |new-group = [
         |  "org.scalameta::munit:1.0.0:test"
         |]
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  withDependenciesFile("").test("write removes duplicate dependencies") { file =>
    val dependencies = List(
      Dependency.WithNumericVersion(
        "org",
        "lib",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      ),
      Dependency.WithNumericVersion(
        "org",
        "lib",
        Version.Numeric(List(2, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      ), // duplicate artifact, different version
      Dependency.WithNumericVersion(
        "org",
        "other",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )
    )

    DependenciesFile.write(file, "group", dependencies)

    val content  = IO.read(file)
    val depCount = content.linesIterator.count(_.trim.startsWith("\""))

    assertEquals(depCount, 2) // Only 2 unique artifacts
  }

  // --- Advanced format tests ---

  withDependenciesFile {
    """|my-project {
       |  dependencies = [
       |    "org.typelevel::cats-core:2.10.0"
       |    "org.scalameta::munit:1.2.1:test"
       |  ]
       |}
       |""".stripMargin
  }.test("read advanced format HOCON file") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    assertEquals(result.length, 2)
    assertEquals(result.map(_.name).sorted, List("cats-core", "munit"))
  }

  withDependenciesFile {
    """|simple-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |
       |advanced-project {
       |  dependencies = [
       |    "org.scalameta::munit:1.2.1:test"
       |  ]
       |}
       |""".stripMargin
  }.test("read mixed format HOCON file") { file =>
    val simple   = DependenciesFile.read(file, "simple-project", variableResolvers)
    val advanced = DependenciesFile.read(file, "advanced-project", variableResolvers)

    assertEquals(simple.length, 1)
    assertEquals(simple.head.name, "cats-core")
    assertEquals(advanced.length, 1)
    assertEquals(advanced.head.name, "munit")
  }

  withDependenciesFile {
    """|my-project {
       |  dependencies = []
       |}
       |""".stripMargin
  }.test("read advanced format with empty dependencies") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    assertEquals(result, List.empty)
  }

  withDependenciesFile {
    """|my-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |""".stripMargin
  }.test("write preserves simple format") { file =>
    val newDeps = List(
      Dependency.WithNumericVersion(
        "org.typelevel",
        "cats-effect",
        Version.Numeric(List(3, 5, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )
    )

    DependenciesFile.write(file, "my-project", newDeps)

    val content = IO.read(file)

    val expected =
      """|my-project = [
         |  "org.typelevel::cats-effect:3.5.0"
         |]
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  withDependenciesFile {
    """|my-project {
       |  dependencies = [
       |    "org.typelevel::cats-core:2.10.0"
       |  ]
       |}
       |""".stripMargin
  }.test("write preserves advanced format") { file =>
    val newDeps = List(
      Dependency.WithNumericVersion(
        "org.typelevel",
        "cats-effect",
        Version.Numeric(List(3, 5, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )
    )

    DependenciesFile.write(file, "my-project", newDeps)

    val content = IO.read(file)

    val expected =
      """|my-project {
         |  dependencies = [
         |    "org.typelevel::cats-effect:3.5.0"
         |  ]
         |}
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  withDependenciesFile {
    """|simple-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |
       |advanced-project {
       |  dependencies = [
       |    "org.scalameta::munit:1.2.1:test"
       |  ]
       |}
       |""".stripMargin
  }.test("write preserves format in mixed file") { file =>
    val simpleDeps = List(
      Dependency.WithNumericVersion(
        "org.typelevel",
        "cats-effect",
        Version.Numeric(List(3, 5, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )
    )
    val advancedDeps = List(
      Dependency.WithNumericVersion(
        "org.scalatest",
        "scalatest",
        Version.Numeric(List(3, 2, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true,
        "test"
      )
    )

    DependenciesFile.write(file, "simple-project", simpleDeps)
    DependenciesFile.write(file, "advanced-project", advancedDeps)

    val content = IO.read(file)

    val expected =
      """|advanced-project {
         |  dependencies = [
         |    "org.scalatest::scalatest:3.2.0:test"
         |  ]
         |}
         |
         |simple-project = [
         |  "org.typelevel::cats-effect:3.5.0"
         |]
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  withDependenciesFile("").test("write then read round-trip preserves advanced format") { file =>
    val deps = List(
      Dependency.WithNumericVersion(
        "org.typelevel",
        "cats-core",
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )
    )

    // First write in simple format (default)
    DependenciesFile.write(file, "my-project", deps)

    // Now manually create an advanced format file
    IO.write(
      file,
      """|my-project {
         |  dependencies = [
         |    "org.typelevel::cats-core:2.10.0"
         |  ]
         |}
         |""".stripMargin
    )

    // Write again - should preserve advanced format
    DependenciesFile.write(file, "my-project", deps)

    val content = IO.read(file)

    val expected =
      """|my-project {
         |  dependencies = [
         |    "org.typelevel::cats-core:2.10.0"
         |  ]
         |}
         |""".stripMargin

    assertNoDiff(content, expected)

    val result = DependenciesFile.read(file, "my-project", variableResolvers)
    assertEquals(result.length, 1)
    assertEquals(result.head.name, "cats-core")
  }

  withDependenciesFile("").test("write new group uses simple format") { file =>
    val deps = List(
      Dependency.WithNumericVersion(
        "org.typelevel",
        "cats-core",
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )
    )

    DependenciesFile.write(file, "new-project", deps)

    val content = IO.read(file)

    val expected =
      """|new-project = [
         |  "org.typelevel::cats-core:2.10.0"
         |]
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  withDependenciesFile {
    """|my-project {
       |  dependencies = [
       |    "org.typelevel::cats-core:2.10.0"
       |  ]
       |  unknownField = "someValue"
       |}
       |""".stripMargin
  }.test("read ignores unknown fields in advanced format") { file =>
    val result = DependenciesFile.read(file, "my-project", variableResolvers)

    assertEquals(result.length, 1)
    assertEquals(result.head.name, "cats-core")
  }

  withDependenciesFile {
    """|my-project {
       |  dependencies = [
       |    "org.typelevel::cats-core:=2.10.0"
       |    "org.typelevel::cats-effect:^3.5.0"
       |  ]
       |}
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

  // --- scalaVersions tests ---

  withDependenciesFile {
    """|my-project {
       |  scala-versions = ["2.13.12", "2.12.18", "3.3.1"]
       |  dependencies = [
       |    "org.typelevel::cats-core:2.10.0"
       |  ]
       |}
       |""".stripMargin
  }.test("readScalaVersions returns scala versions from advanced format") { file =>
    val result = DependenciesFile.readScalaVersions(file, "my-project")

    assertEquals(result, List(v("2.13.12"), v("2.12.18"), v("3.3.1")))
  }

  withDependenciesFile {
    """|my-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |""".stripMargin
  }.test("readScalaVersions returns empty list for simple format") { file =>
    val result = DependenciesFile.readScalaVersions(file, "my-project")

    assertEquals(result, List.empty)
  }

  withDependenciesFile {
    """|my-project {
       |  dependencies = [
       |    "org.typelevel::cats-core:2.10.0"
       |  ]
       |}
       |""".stripMargin
  }.test("readScalaVersions returns empty list when not specified in advanced format") { file =>
    val result = DependenciesFile.readScalaVersions(file, "my-project")

    assertEquals(result, List.empty)
  }

  nonExistentFile.test("readScalaVersions returns empty list for non-existent file") { file =>
    val result = DependenciesFile.readScalaVersions(file, "my-project")

    assertEquals(result, List.empty)
  }

  withDependenciesFile {
    """|my-project {
       |  scala-versions = ["2.13.12", "2.12.18"]
       |  dependencies = [
       |    "org.typelevel::cats-core:2.10.0"
       |  ]
       |}
       |""".stripMargin
  }.test("write preserves scalaVersions in advanced format") { file =>
    val newDeps = List(
      Dependency.WithNumericVersion(
        "org.typelevel",
        "cats-effect",
        Version.Numeric(List(3, 5, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )
    )

    DependenciesFile.write(file, "my-project", newDeps)

    val content = IO.read(file)

    val expected =
      """|my-project {
         |  scala-versions = ["2.13.12", "2.12.18"]
         |  dependencies = [
         |    "org.typelevel::cats-effect:3.5.0"
         |  ]
         |}
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  withDependenciesFile {
    """|my-project {
       |  scala-version = "2.13.12"
       |  dependencies = [
       |    "org.typelevel::cats-core:2.10.0"
       |  ]
       |}
       |""".stripMargin
  }.test("readScalaVersions then write round-trip preserves scalaVersions") { file =>
    val versions = DependenciesFile.readScalaVersions(file, "my-project")
    assertEquals(versions, List(v("2.13.12")))

    val deps = DependenciesFile.read(file, "my-project", variableResolvers)
    DependenciesFile.write(file, "my-project", deps)

    val content = IO.read(file)

    val expected =
      """|my-project {
         |  scala-version = "2.13.12"
         |  dependencies = [
         |    "org.typelevel::cats-core:2.10.0"
         |  ]
         |}
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  withDependenciesFile {
    """|simple-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |
       |advanced-project {
       |  scala-versions = ["3.3.1"]
       |  dependencies = [
       |    "org.scalameta::munit:1.2.1:test"
       |  ]
       |}
       |""".stripMargin
  }.test("readScalaVersions works with mixed format file") { file =>
    val simpleVersions   = DependenciesFile.readScalaVersions(file, "simple-project")
    val advancedVersions = DependenciesFile.readScalaVersions(file, "advanced-project")

    assertEquals(simpleVersions, List.empty[Numeric])
    assertEquals(advancedVersions, List(v("3.3.1")))
  }

  // --- writeScalaVersions tests ---

  withDependenciesFile("").test("writeScalaVersions creates advanced format with scala-versions") { file =>
    DependenciesFile.writeScalaVersions(file, "my-project", List(v("2.13.12"), v("2.12.18")))

    val content = IO.read(file)

    val expected =
      """|my-project {
         |  scala-versions = ["~2.13.12", "~2.12.18"]
         |  dependencies = []
         |}
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  withDependenciesFile {
    """|my-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |""".stripMargin
  }.test("writeScalaVersions preserves existing dependencies from simple format") { file =>
    DependenciesFile.writeScalaVersions(file, "my-project", List(v("2.13.12")))

    val content = IO.read(file)

    val expected =
      """|my-project {
         |  scala-version = "~2.13.12"
         |  dependencies = [
         |    "org.typelevel::cats-core:2.10.0"
         |  ]
         |}
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  withDependenciesFile {
    """|my-project {
       |  scala-versions = ["2.12.18"]
       |  dependencies = [
       |    "org.typelevel::cats-core:2.10.0"
       |  ]
       |}
       |""".stripMargin
  }.test("writeScalaVersions updates existing scala-versions") { file =>
    DependenciesFile.writeScalaVersions(file, "my-project", List(v("2.13.14"), v("3.3.3")))

    val content = IO.read(file)

    val expected =
      """|my-project {
         |  scala-versions = ["~2.13.14", "~3.3.3"]
         |  dependencies = [
         |    "org.typelevel::cats-core:2.10.0"
         |  ]
         |}
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  withDependenciesFile {
    """|other-project = [
       |  "org.scalameta::munit:1.2.1:test"
       |]
       |""".stripMargin
  }.test("writeScalaVersions preserves other groups") { file =>
    DependenciesFile.writeScalaVersions(file, "my-project", List(v("2.13.12")))

    val content = IO.read(file)

    val expected =
      """|my-project {
         |  scala-version = "~2.13.12"
         |  dependencies = []
         |}
         |
         |other-project = [
         |  "org.scalameta::munit:1.2.1:test"
         |]
         |""".stripMargin

    assertNoDiff(content, expected)
  }

  withDependenciesFile {
    """|my-project {
       |  scala-versions = ["2.13.12"]
       |  dependencies = [
       |    "org.typelevel::cats-core:2.10.0"
       |  ]
       |}
       |""".stripMargin
  }.test("writeScalaVersions then readScalaVersions round-trip") { file =>
    val originalVersions = DependenciesFile.readScalaVersions(file, "my-project")
    assertEquals(originalVersions, List(v("2.13.12")))

    DependenciesFile.writeScalaVersions(file, "my-project", List(v("2.13.14"), v("3.3.3")))

    val updatedVersions = DependenciesFile.readScalaVersions(file, "my-project")
    assertEquals(updatedVersions, List(v("2.13.14"), v("3.3.3")))

    // Verify dependencies are still preserved
    val deps = DependenciesFile.read(file, "my-project", variableResolvers)
    assertEquals(deps.length, 1)
    assertEquals(deps.head.name, "cats-core")
  }

  // --- hasGroup tests ---

  withDependenciesFile {
    """|my-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |
       |other-project = [
       |  "org.scalameta::munit:1.2.1:test"
       |]
       |""".stripMargin
  }.test("hasGroup returns true for existing group") { file =>
    assert(DependenciesFile.hasGroup(file, "my-project"))
    assert(DependenciesFile.hasGroup(file, "other-project"))
  }

  withDependenciesFile {
    """|my-project = [
       |  "org.typelevel::cats-core:2.10.0"
       |]
       |""".stripMargin
  }.test("hasGroup returns false for non-existent group") { file =>
    assert(!DependenciesFile.hasGroup(file, "non-existent"))
  }

  nonExistentFile.test("hasGroup returns false for non-existent file") { file =>
    assert(!DependenciesFile.hasGroup(file, "any-group"))
  }

  withDependenciesFile("").test("hasGroup returns false for empty file") { file =>
    assert(!DependenciesFile.hasGroup(file, "any-group"))
  }

  withDependenciesFile {
    """|my-project {
       |  dependencies = []
       |}
       |""".stripMargin
  }.test("hasGroup returns true for group with empty dependencies") { file =>
    assert(DependenciesFile.hasGroup(file, "my-project"))
  }

}
