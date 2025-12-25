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

import sbt.librarymanagement.CrossVersion
import sbt.util.Level
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Dependency.Version

class DependencySuite extends munit.FunSuite {

  implicit val logger: Logger = new Logger {
    override def trace(t: => Throwable): Unit                      = ()
    override def success(message: => String): Unit                 = ()
    override def log(level: Level.Value, message: => String): Unit = ()
  }

  // --- withVersion tests ---

  test("withVersion returns copy with new version") {
    val dep = Dependency(
      "org.typelevel",
      "cats-core",
      Version(List(2, 10, 0), None, Version.Marker.NoMarker),
      isCross = true,
      "test"
    )

    val newVersion = Version(List(2, 11, 0), None, Version.Marker.NoMarker)
    val result     = dep.withVersion(newVersion)

    assertEquals(result.version, newVersion)
    assertEquals(result.organization, dep.organization)
    assertEquals(result.name, dep.name)
  }

  // --- toModuleID tests ---

  test("toModuleID creates cross-compiled module") {
    val dep = Dependency(
      "org.typelevel",
      "cats-core",
      Version(List(2, 10, 0), None, Version.Marker.NoMarker),
      isCross = true,
      "test"
    )

    val module = dep.toModuleID("1.0", "2.13")

    assertEquals(module.organization, "org.typelevel")
    assertEquals(module.name, "cats-core")
    assertEquals(module.revision, "2.10.0")
    assert(module.crossVersion.isInstanceOf[CrossVersion.Binary])
  }

  test("toModuleID creates java module without cross version") {
    val dep = Dependency(
      "com.google.guava",
      "guava",
      Version(List(32, 1, 0), Some("-jre"), Version.Marker.NoMarker),
      isCross = false,
      "test"
    )

    val module = dep.toModuleID("1.0", "2.13")

    assertEquals(module.organization, "com.google.guava")
    assertEquals(module.name, "guava")
    assertEquals(module.revision, "32.1.0-jre")
    assert(module.crossVersion.isInstanceOf[CrossVersion.Disabled.type])
  }

  test("toModuleID sets test configuration") {
    val dep = Dependency(
      "org.scalameta",
      "munit",
      Version(List(1, 2, 1), None, Version.Marker.NoMarker),
      isCross = true,
      "test",
      "test"
    )

    val module = dep.toModuleID("1.0", "2.13")

    assertEquals(module.configurations, Some("test"))
  }

  test("toModuleID omits compile configuration") {
    val dep = Dependency(
      "org.typelevel",
      "cats-core",
      Version(List(2, 10, 0), None, Version.Marker.NoMarker),
      isCross = true,
      "test",
      "compile"
    )

    val module = dep.toModuleID("1.0", "2.13")

    assertEquals(module.configurations, None)
  }

  test("toModuleID creates sbt plugin module") {
    val dep = Dependency(
      "ch.epfl.scala",
      "sbt-scalafix",
      Version(List(0, 14, 5), None, Version.Marker.NoMarker),
      isCross = false,
      "sbt-build",
      "sbt-plugin"
    )

    val module = dep.toModuleID("1.0", "2.12")

    assertEquals(module.organization, "ch.epfl.scala")
    assertEquals(module.name, "sbt-scalafix")
    assertEquals(module.revision, "0.14.5")
    // sbt plugins get extra attributes via sbtPluginExtra
    assert(module.extraAttributes.nonEmpty || module.crossVersion != CrossVersion.disabled)
  }

  // --- isSameArtifact tests ---

  test("isSameArtifact returns true for same artifact") {
    val dep1 = Dependency("org", "name", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = true, "group")
    val dep2 = Dependency("org", "name", Version(List(2, 0, 0), None, Version.Marker.NoMarker), isCross = true, "group")

    assert(dep1.isSameArtifact(dep2))
  }

  test("isSameArtifact returns false for different organization") {
    val dep1 =
      Dependency("org1", "name", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = true, "group")
    val dep2 =
      Dependency("org2", "name", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = true, "group")

    assert(!dep1.isSameArtifact(dep2))
  }

  test("isSameArtifact returns false for different name") {
    val dep1 =
      Dependency("org", "name1", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = true, "group")
    val dep2 =
      Dependency("org", "name2", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = true, "group")

    assert(!dep1.isSameArtifact(dep2))
  }

  test("isSameArtifact returns false for different isCross") {
    val dep1 = Dependency("org", "name", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = true, "group")
    val dep2 =
      Dependency("org", "name", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "group")

    assert(!dep1.isSameArtifact(dep2))
  }

  test("isSameArtifact returns false for different group") {
    val dep1 =
      Dependency("org", "name", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = true, "group1")
    val dep2 =
      Dependency("org", "name", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = true, "group2")

    assert(!dep1.isSameArtifact(dep2))
  }

  // --- toLine tests ---

  test("toLine formats cross-compiled dependency") {
    val dep = Dependency(
      "org.typelevel",
      "cats-core",
      Version(List(2, 10, 0), None, Version.Marker.NoMarker),
      isCross = true,
      "test"
    )

    assertEquals(dep.toLine, "org.typelevel::cats-core:2.10.0")
  }

  test("toLine formats java dependency") {
    val dep = Dependency(
      "com.google.guava",
      "guava",
      Version(List(32, 1, 0), Some("-jre"), Version.Marker.NoMarker),
      isCross = false,
      "test"
    )

    assertEquals(dep.toLine, "com.google.guava:guava:32.1.0-jre")
  }

  test("toLine includes configuration suffix") {
    val dep = Dependency(
      "org.scalameta",
      "munit",
      Version(List(1, 2, 1), None, Version.Marker.NoMarker),
      isCross = true,
      "test",
      "test"
    )

    assertEquals(dep.toLine, "org.scalameta::munit:1.2.1:test")
  }

  test("toLine omits compile configuration") {
    val dep = Dependency(
      "org.typelevel",
      "cats-core",
      Version(List(2, 10, 0), None, Version.Marker.NoMarker),
      isCross = true,
      "test",
      "compile"
    )

    assertEquals(dep.toLine, "org.typelevel::cats-core:2.10.0")
  }

  test("toLine includes sbt-plugin configuration") {
    val dep = Dependency(
      "ch.epfl.scala",
      "sbt-scalafix",
      Version(List(0, 14, 5), None, Version.Marker.NoMarker),
      isCross = false,
      "sbt-build",
      "sbt-plugin"
    )

    assertEquals(dep.toLine, "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin")
  }

  test("toLine includes version marker") {
    val dep = Dependency(
      "org.typelevel",
      "cats-core",
      Version(List(2, 10, 0), None, Version.Marker.Exact),
      isCross = true,
      "test"
    )

    assertEquals(dep.toLine, "org.typelevel::cats-core:=2.10.0")
  }

  // --- update tests ---

  test("update returns same version when marker is Exact") {
    implicit val versionFinder: Utils.VersionFinder =
      (_, _, _, _) => List(Version(List(3, 0, 0), None, Version.Marker.NoMarker))

    val dep = Dependency(
      "org.typelevel",
      "cats-core",
      Version(List(2, 10, 0), None, Version.Marker.Exact),
      isCross = true,
      "test"
    )

    val result = dep.update

    assertEquals(result.toVersionString, "2.10.0")
    assertEquals(result.marker, Version.Marker.Exact)
  }

  test("update finds latest version and preserves marker") {
    implicit val versionFinder: Utils.VersionFinder = (_, _, _, _) =>
      List(
        Version(List(2, 10, 0), None, Version.Marker.NoMarker),
        Version(List(2, 11, 0), None, Version.Marker.NoMarker),
        Version(List(2, 12, 0), None, Version.Marker.NoMarker)
      )

    val dep = Dependency(
      "org.typelevel",
      "cats-core",
      Version(List(2, 10, 0), None, Version.Marker.Major),
      isCross = true,
      "test"
    )

    val result = dep.update

    assertEquals(result.toVersionString, "2.12.0")
    assertEquals(result.marker, Version.Marker.Major)
  }

  test("update respects major version constraint") {
    implicit val versionFinder: Utils.VersionFinder = (_, _, _, _) =>
      List(
        Version(List(2, 10, 0), None, Version.Marker.NoMarker),
        Version(List(2, 11, 0), None, Version.Marker.NoMarker),
        Version(List(3, 0, 0), None, Version.Marker.NoMarker)
      )

    val dep = Dependency(
      "org.typelevel",
      "cats-core",
      Version(List(2, 10, 0), None, Version.Marker.Major),
      isCross = true,
      "test"
    )

    val result = dep.update

    assertEquals(result.toVersionString, "2.11.0")
  }

  test("update respects minor version constraint") {
    implicit val versionFinder: Utils.VersionFinder = (_, _, _, _) =>
      List(
        Version(List(2, 10, 0), None, Version.Marker.NoMarker),
        Version(List(2, 10, 5), None, Version.Marker.NoMarker),
        Version(List(2, 11, 0), None, Version.Marker.NoMarker)
      )

    val dep = Dependency(
      "org.typelevel",
      "cats-core",
      Version(List(2, 10, 0), None, Version.Marker.Minor),
      isCross = true,
      "test"
    )

    val result = dep.update

    assertEquals(result.toVersionString, "2.10.5")
  }

  // --- scalaVersionSuffix tests ---

  test("scalaVersionSuffix returns None for regular artifact") {
    val dep = Dependency("org", "cats-core", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = true, "g")

    assertEquals(dep.scalaVersionSuffix, None)
  }

  test("scalaVersionSuffix returns Some(2.13) for _2.13 suffix") {
    val dep =
      Dependency("org", "cats-core_2.13", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "g")

    assertEquals(dep.scalaVersionSuffix, Some("2.13"))
  }

  test("scalaVersionSuffix returns Some(2.12) for _2.12 suffix") {
    val dep =
      Dependency("org", "cats-core_2.12", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "g")

    assertEquals(dep.scalaVersionSuffix, Some("2.12"))
  }

  test("scalaVersionSuffix returns Some(3) for _3 suffix") {
    val dep =
      Dependency("org", "cats-core_3", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "g")

    assertEquals(dep.scalaVersionSuffix, Some("3"))
  }

  test("scalaVersionSuffix returns Some(2.11) for _2.11 suffix") {
    val dep =
      Dependency("org", "cats-core_2.11", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "g")

    assertEquals(dep.scalaVersionSuffix, Some("2.11"))
  }

  // --- matchesScalaVersion tests ---

  test("matchesScalaVersion returns true for regular artifact with any Scala version") {
    val dep = Dependency("org", "cats-core", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = true, "g")

    assert(dep.matchesScalaVersion("2.13"))
    assert(dep.matchesScalaVersion("2.12"))
    assert(dep.matchesScalaVersion("3"))
  }

  test("matchesScalaVersion returns true when suffix matches Scala version") {
    val dep213 =
      Dependency("org", "cats-core_2.13", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "g")
    val dep212 =
      Dependency("org", "cats-core_2.12", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "g")
    val dep3 =
      Dependency("org", "cats-core_3", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "g")

    assert(dep213.matchesScalaVersion("2.13"))
    assert(dep212.matchesScalaVersion("2.12"))
    assert(dep3.matchesScalaVersion("3"))
  }

  test("matchesScalaVersion returns false when suffix does not match Scala version") {
    val dep213 =
      Dependency("org", "cats-core_2.13", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "g")
    val dep212 =
      Dependency("org", "cats-core_2.12", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "g")
    val dep3 =
      Dependency("org", "cats-core_3", Version(List(1, 0, 0), None, Version.Marker.NoMarker), isCross = false, "g")

    assert(!dep213.matchesScalaVersion("2.12"))
    assert(!dep213.matchesScalaVersion("3"))
    assert(!dep212.matchesScalaVersion("2.13"))
    assert(!dep212.matchesScalaVersion("3"))
    assert(!dep3.matchesScalaVersion("2.13"))
    assert(!dep3.matchesScalaVersion("2.12"))
  }

}
