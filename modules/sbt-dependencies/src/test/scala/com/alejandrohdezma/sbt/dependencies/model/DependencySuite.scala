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

package com.alejandrohdezma.sbt.dependencies.model

import sbt.librarymanagement.CrossVersion
import sbt.librarymanagement.ModuleID
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.MigrationFinder
import com.alejandrohdezma.sbt.dependencies.TestLogger
import com.alejandrohdezma.sbt.dependencies.VersionFinder
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version

class DependencySuite extends munit.FunSuite {

  implicit val migrationFinder: MigrationFinder = _ => None

  implicit val logger: Logger = TestLogger()

  // --- withVersion tests ---

  test("withVersion returns copy with new version") {
    val dep = Dependency.WithNumericVersion(
      "org.typelevel",
      "cats-core",
      Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = true
    )

    val newVersion = Version.Numeric(List(2, 11, 0), None, Version.Numeric.Marker.NoMarker)
    val result     = dep.withVersion(newVersion)

    assertEquals(result.version, newVersion)
    assertEquals(result.organization, dep.organization)
    assertEquals(result.name, dep.name)
  }

  // --- toModuleID tests ---

  test("toModuleID creates cross-compiled module") {
    val dep = Dependency.WithNumericVersion(
      "org.typelevel",
      "cats-core",
      Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = true
    )

    val module = dep.toModuleID("1.0", "2.13")

    assertEquals(module.organization, "org.typelevel")
    assertEquals(module.name, "cats-core")
    assertEquals(module.revision, "2.10.0")
    assert(module.crossVersion.isInstanceOf[CrossVersion.Binary])
  }

  test("toModuleID creates java module without cross version") {
    val dep = Dependency.WithNumericVersion(
      "com.google.guava",
      "guava",
      Version.Numeric(List(32, 1, 0), Some("-jre"), Version.Numeric.Marker.NoMarker),
      isCross = false
    )

    val module = dep.toModuleID("1.0", "2.13")

    assertEquals(module.organization, "com.google.guava")
    assertEquals(module.name, "guava")
    assertEquals(module.revision, "32.1.0-jre")
    assert(module.crossVersion.isInstanceOf[CrossVersion.Disabled.type])
  }

  test("toModuleID sets test configuration") {
    val dep = Dependency.WithNumericVersion(
      "org.scalameta",
      "munit",
      Version.Numeric(List(1, 2, 1), None, Version.Numeric.Marker.NoMarker),
      isCross = true,
      "test"
    )

    val module = dep.toModuleID("1.0", "2.13")

    assertEquals(module.configurations, Some("test"))
  }

  test("toModuleID omits compile configuration") {
    val dep = Dependency.WithNumericVersion(
      "org.typelevel",
      "cats-core",
      Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = true,
      "compile"
    )

    val module = dep.toModuleID("1.0", "2.13")

    assertEquals(module.configurations, None)
  }

  test("toModuleID creates sbt plugin module") {
    val dep = Dependency.WithNumericVersion(
      "ch.epfl.scala",
      "sbt-scalafix",
      Version.Numeric(List(0, 14, 5), None, Version.Numeric.Marker.NoMarker),
      isCross = false,
      "sbt-plugin"
    )

    val module = dep.toModuleID("1.0", "2.12")

    assertEquals(module.organization, "ch.epfl.scala")
    assertEquals(module.name, "sbt-scalafix")
    assertEquals(module.revision, "0.14.5")
    // sbt plugins get extra attributes via sbtPluginExtra
    assert(module.extraAttributes.nonEmpty || module.crossVersion != CrossVersion.disabled) // scalafix:ok
  }

  // --- isSameArtifact tests ---

  test("isSameArtifact returns true for same artifact") {
    val dep1 = Dependency.WithNumericVersion(
      "org",
      "name",
      Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = true
    )
    val dep2 = Dependency.WithNumericVersion(
      "org",
      "name",
      Version.Numeric(List(2, 0, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = true
    )

    assert(dep1.isSameArtifact(dep2))
  }

  test("isSameArtifact returns false for different organization") {
    val dep1 =
      Dependency.WithNumericVersion(
        "org1",
        "name",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )
    val dep2 =
      Dependency.WithNumericVersion(
        "org2",
        "name",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )

    assert(!dep1.isSameArtifact(dep2))
  }

  test("isSameArtifact returns false for different name") {
    val dep1 =
      Dependency.WithNumericVersion(
        "org",
        "name1",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )
    val dep2 =
      Dependency.WithNumericVersion(
        "org",
        "name2",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = true
      )

    assert(!dep1.isSameArtifact(dep2))
  }

  test("isSameArtifact returns false for different isCross") {
    val dep1 = Dependency.WithNumericVersion(
      "org",
      "name",
      Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = true
    )
    val dep2 =
      Dependency.WithNumericVersion(
        "org",
        "name",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )

    assert(!dep1.isSameArtifact(dep2))
  }

  test("isSameArtifact returns false for different configuration") {
    val dep1 = Dependency.WithNumericVersion(
      "org",
      "name",
      Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = false
    )
    val dep2 = Dependency.WithNumericVersion(
      "org",
      "name",
      Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = false,
      "protobuf"
    )

    assert(!dep1.isSameArtifact(dep2))
  }

  // --- fromModuleID tests ---

  test("fromModuleID preserves custom configuration") {
    val moduleID = ModuleID("com.google.protobuf", "protobuf-java", "3.25.1")
      .withConfigurations(Some("protobuf"))

    val result = Dependency.fromModuleID(moduleID)

    assert(result.isDefined)
    assertEquals(result.get.configuration, "protobuf")
    assertEquals(result.get.toLine, "com.google.protobuf:protobuf-java:3.25.1:protobuf")
  }

  test("fromModuleID defaults to compile when no configuration") {
    val moduleID = ModuleID("org.typelevel", "cats-core", "2.10.0")
      .withCrossVersion(sbt.librarymanagement.CrossVersion.binary)

    val result = Dependency.fromModuleID(moduleID)

    assert(result.isDefined)
    assertEquals(result.get.configuration, "compile")
  }

  // --- toLine tests ---

  test("toLine formats cross-compiled dependency") {
    val dep = Dependency.WithNumericVersion(
      "org.typelevel",
      "cats-core",
      Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = true
    )

    assertEquals(dep.toLine, "org.typelevel::cats-core:2.10.0")
  }

  test("toLine formats java dependency") {
    val dep = Dependency.WithNumericVersion(
      "com.google.guava",
      "guava",
      Version.Numeric(List(32, 1, 0), Some("-jre"), Version.Numeric.Marker.NoMarker),
      isCross = false
    )

    assertEquals(dep.toLine, "com.google.guava:guava:32.1.0-jre")
  }

  test("toLine includes configuration suffix") {
    val dep = Dependency.WithNumericVersion(
      "org.scalameta",
      "munit",
      Version.Numeric(List(1, 2, 1), None, Version.Numeric.Marker.NoMarker),
      isCross = true,
      "test"
    )

    assertEquals(dep.toLine, "org.scalameta::munit:1.2.1:test")
  }

  test("toLine omits compile configuration") {
    val dep = Dependency.WithNumericVersion(
      "org.typelevel",
      "cats-core",
      Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = true,
      "compile"
    )

    assertEquals(dep.toLine, "org.typelevel::cats-core:2.10.0")
  }

  test("toLine includes sbt-plugin configuration") {
    val dep = Dependency.WithNumericVersion(
      "ch.epfl.scala",
      "sbt-scalafix",
      Version.Numeric(List(0, 14, 5), None, Version.Numeric.Marker.NoMarker),
      isCross = false,
      "sbt-plugin"
    )

    assertEquals(dep.toLine, "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin")
  }

  test("toLine includes version marker") {
    val dep = Dependency.WithNumericVersion(
      "org.typelevel",
      "cats-core",
      Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.Exact),
      isCross = true
    )

    assertEquals(dep.toLine, "org.typelevel::cats-core:=2.10.0")
  }

  // --- findLatestVersion tests ---

  test("findLatestVersion returns same version when marker is Exact") {
    implicit val versionFinder: VersionFinder =
      (_, _, _, _) => List(Version.Numeric(List(3, 0, 0), None, Version.Numeric.Marker.NoMarker))

    val dep = Dependency.WithNumericVersion(
      "org.typelevel",
      "cats-core",
      Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.Exact),
      isCross = true
    )

    val result = dep.findLatestVersion

    assertEquals(result.version.toVersionString, "2.10.0")
    assertEquals(result.version.marker, Version.Numeric.Marker.Exact)
  }

  test("findLatestVersion finds latest version and preserves marker") {
    implicit val versionFinder: VersionFinder = (_, _, _, _) =>
      List(
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
        Version.Numeric(List(2, 11, 0), None, Version.Numeric.Marker.NoMarker),
        Version.Numeric(List(2, 12, 0), None, Version.Numeric.Marker.NoMarker)
      )

    val dep = Dependency.WithNumericVersion(
      "org.typelevel",
      "cats-core",
      Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.Major),
      isCross = true
    )

    val result = dep.findLatestVersion

    assertEquals(result.version.toVersionString, "2.12.0")
    assertEquals(result.version.marker, Version.Numeric.Marker.Major)
  }

  test("findLatestVersion respects major version constraint") {
    implicit val versionFinder: VersionFinder = (_, _, _, _) =>
      List(
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
        Version.Numeric(List(2, 11, 0), None, Version.Numeric.Marker.NoMarker),
        Version.Numeric(List(3, 0, 0), None, Version.Numeric.Marker.NoMarker)
      )

    val dep = Dependency.WithNumericVersion(
      "org.typelevel",
      "cats-core",
      Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.Major),
      isCross = true
    )

    val result = dep.findLatestVersion

    assertEquals(result.version.toVersionString, "2.11.0")
  }

  test("findLatestVersion respects minor version constraint") {
    implicit val versionFinder: VersionFinder = (_, _, _, _) =>
      List(
        Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.NoMarker),
        Version.Numeric(List(2, 10, 5), None, Version.Numeric.Marker.NoMarker),
        Version.Numeric(List(2, 11, 0), None, Version.Numeric.Marker.NoMarker)
      )

    val dep = Dependency.WithNumericVersion(
      "org.typelevel",
      "cats-core",
      Version.Numeric(List(2, 10, 0), None, Version.Numeric.Marker.Minor),
      isCross = true
    )

    val result = dep.findLatestVersion

    assertEquals(result.version.toVersionString, "2.10.5")
  }

  // --- scalaVersionSuffix tests ---

  test("scalaVersionSuffix returns None for regular artifact") {
    val dep = Dependency.WithNumericVersion(
      "org",
      "cats-core",
      Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = true
    )

    assertEquals(dep.scalaVersionSuffix, None)
  }

  test("scalaVersionSuffix returns Some(2.13) for _2.13 suffix") {
    val dep =
      Dependency.WithNumericVersion(
        "org",
        "cats-core_2.13",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )

    assertEquals(dep.scalaVersionSuffix, Some("2.13"))
  }

  test("scalaVersionSuffix returns Some(2.12) for _2.12 suffix") {
    val dep =
      Dependency.WithNumericVersion(
        "org",
        "cats-core_2.12",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )

    assertEquals(dep.scalaVersionSuffix, Some("2.12"))
  }

  test("scalaVersionSuffix returns Some(3) for _3 suffix") {
    val dep =
      Dependency.WithNumericVersion(
        "org",
        "cats-core_3",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )

    assertEquals(dep.scalaVersionSuffix, Some("3"))
  }

  test("scalaVersionSuffix returns Some(2.11) for _2.11 suffix") {
    val dep =
      Dependency.WithNumericVersion(
        "org",
        "cats-core_2.11",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )

    assertEquals(dep.scalaVersionSuffix, Some("2.11"))
  }

  // --- matchesScalaVersion tests ---

  test("matchesScalaVersion returns true for regular artifact with any Scala version") {
    val dep = Dependency.WithNumericVersion(
      "org",
      "cats-core",
      Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
      isCross = true
    )

    assert(dep.matchesScalaVersion("2.13"))
    assert(dep.matchesScalaVersion("2.12"))
    assert(dep.matchesScalaVersion("3"))
  }

  test("matchesScalaVersion returns true when suffix matches Scala version") {
    val dep213 =
      Dependency.WithNumericVersion(
        "org",
        "cats-core_2.13",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )
    val dep212 =
      Dependency.WithNumericVersion(
        "org",
        "cats-core_2.12",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )
    val dep3 =
      Dependency.WithNumericVersion(
        "org",
        "cats-core_3",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )

    assert(dep213.matchesScalaVersion("2.13"))
    assert(dep212.matchesScalaVersion("2.12"))
    assert(dep3.matchesScalaVersion("3"))
  }

  test("matchesScalaVersion returns false when suffix does not match Scala version") {
    val dep213 =
      Dependency.WithNumericVersion(
        "org",
        "cats-core_2.13",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )
    val dep212 =
      Dependency.WithNumericVersion(
        "org",
        "cats-core_2.12",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )
    val dep3 =
      Dependency.WithNumericVersion(
        "org",
        "cats-core_3",
        Version.Numeric(List(1, 0, 0), None, Version.Numeric.Marker.NoMarker),
        isCross = false
      )

    assert(!dep213.matchesScalaVersion("2.12"))
    assert(!dep213.matchesScalaVersion("3"))
    assert(!dep212.matchesScalaVersion("2.13"))
    assert(!dep212.matchesScalaVersion("3"))
    assert(!dep3.matchesScalaVersion("2.13"))
    assert(!dep3.matchesScalaVersion("2.12"))
  }

}
