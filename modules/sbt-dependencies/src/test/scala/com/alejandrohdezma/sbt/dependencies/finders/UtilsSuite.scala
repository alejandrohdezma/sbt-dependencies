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

import scala.Console._

import sbt.util.Level

import com.alejandrohdezma.sbt.dependencies.TestLogger
import com.alejandrohdezma.sbt.dependencies.constraints.ArtifactMigration
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.model.Eq._

class UtilsSuite extends munit.FunSuite {

  implicit val migrationFinder: MigrationFinder = _ => None

  implicit val logger: TestLogger = TestLogger()

  override def beforeEach(context: BeforeEach): Unit = logger.cleanLogs()

  // Helper to parse a version string into Numeric with Minor marker (matching readScalaVersions behavior)
  def v(version: String): Numeric =
    Version.Numeric.unapply(version).get.copy(marker = Numeric.Marker.Minor)

  // Helper to create a Numeric version with NoMarker
  def nv(version: String): Numeric =
    Version.Numeric.unapply(version).get.copy(marker = Numeric.Marker.NoMarker)

  // Helper to create a dependency
  def dep(
      org: String,
      name: String,
      version: String,
      isCross: Boolean = true,
      configuration: String = "compile"
  ): Dependency.WithNumericVersion =
    Dependency.WithNumericVersion(org, name, nv(version), isCross, configuration)

  // Helper to create a dependency with Exact marker
  def exactDep(org: String, name: String, version: String, isCross: Boolean = true): Dependency.WithNumericVersion =
    Dependency.WithNumericVersion(
      org,
      name,
      Version.Numeric.unapply(version).get.copy(marker = Numeric.Marker.Exact),
      isCross
    )

  // Helper to create a mock VersionFinder that returns specific versions for specific modules
  def mockVersionFinder(versions: Map[(String, String), List[String]]): VersionFinder =
    (organization: String, name: String, _: Boolean, _: Boolean) =>
      versions
        .getOrElse((organization, name), Nil)
        .collect { case Version.Numeric(v) => v }

  // --- findLatestScalaVersion tests ---

  test("findLatestScalaVersion finds latest 2.13.x version") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala-library") -> List("2.13.10", "2.13.11", "2.13.12", "2.13.14", "2.12.18")
      )
    )

    val result = Utils.findLatestScalaVersion(v("2.13.12"))

    assertEquals(result, v("2.13.14"))
  }

  test("findLatestScalaVersion finds latest 2.12.x version") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala-library") -> List("2.12.15", "2.12.16", "2.12.17", "2.12.18", "2.12.19", "2.13.12")
      )
    )

    val result = Utils.findLatestScalaVersion(v("2.12.17"))

    assertEquals(result, v("2.12.19"))
  }

  test("findLatestScalaVersion finds latest 3.3.x version") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala3-library_3") -> List("3.3.0", "3.3.1", "3.3.3", "3.4.0", "3.5.0")
      )
    )

    val result = Utils.findLatestScalaVersion(v("3.3.1"))

    assertEquals(result, v("3.3.3"))
  }

  test("findLatestScalaVersion finds latest 3.4.x version") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala3-library_3") -> List("3.3.3", "3.4.0", "3.4.1", "3.4.2", "3.5.0")
      )
    )

    val result = Utils.findLatestScalaVersion(v("3.4.0"))

    assertEquals(result, v("3.4.2"))
  }

  test("findLatestScalaVersion returns same version when already latest") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala-library") -> List("2.13.10", "2.13.11", "2.13.12")
      )
    )

    val result = Utils.findLatestScalaVersion(v("2.13.12"))

    assertEquals(result, v("2.13.12"))
  }

  test("findLatestScalaVersion uses scala-library for Scala 2") {
    // We verify this by providing versions only for scala-library
    // If it queried the wrong module, it would fail to find a version
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala-library") -> List("2.13.14")
      )
    )

    val result = Utils.findLatestScalaVersion(v("2.13.12"))

    assertEquals(result, v("2.13.14"))
  }

  test("findLatestScalaVersion uses scala3-library_3 for Scala 3 before 3.8") {
    // We verify this by providing versions only for scala3-library_3
    // If it queried the wrong module, it would fail to find a version
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala3-library_3") -> List("3.3.3")
      )
    )

    val result = Utils.findLatestScalaVersion(v("3.3.1"))

    assertEquals(result, v("3.3.3"))
  }

  test("findLatestScalaVersion uses scala-library for Scala 3.8+") {
    // From Scala 3.8.0 onwards, the artifact is scala-library
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala-library") -> List("3.8.0", "3.8.1", "3.8.2")
      )
    )

    val result = Utils.findLatestScalaVersion(v("3.8.0"))

    assertEquals(result, v("3.8.2"))
  }

  test("findLatestScalaVersion fails for version without patch") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.scala-lang", "scala-library") -> List("2.13.12", "2.13.14")
      )
    )

    // 2.13 is not a valid Scala version format - it needs a patch component
    // Shape matching in isValidCandidate will reject 3-part versions when current is 2-part
    intercept[RuntimeException] {
      Utils.findLatestScalaVersion(v("2.13"))
    }
  }

  // --- resolveLatestVersions tests ---

  test("resolveLatestVersions returns empty list for empty input") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(Map.empty)

    val result = Utils.resolveLatestVersions(Nil, 1)

    assertEquals(result, Nil)
  }

  test("resolveLatestVersions updates dependency to latest version") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(("org.typelevel", "cats-core") -> List("2.9.0", "2.10.0", "2.11.0"))
    )

    val result = Utils.resolveLatestVersions(List(dep("org.typelevel", "cats-core", "2.9.0")), 1)

    assertEquals(result, List(dep("org.typelevel", "cats-core", "2.11.0")))
  }

  test("resolveLatestVersions keeps dependency when already at latest") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(("org.typelevel", "cats-core") -> List("2.10.0", "2.11.0"))
    )

    val result = Utils.resolveLatestVersions(List(dep("org.typelevel", "cats-core", "2.11.0")), 1)

    assertEquals(result, List(dep("org.typelevel", "cats-core", "2.11.0")))
  }

  test("resolveLatestVersions preserves exact-marker dependencies unchanged") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(("org.typelevel", "cats-core") -> List("2.9.0", "2.10.0", "2.11.0"))
    )

    val input  = exactDep("org.typelevel", "cats-core", "2.9.0")
    val result = Utils.resolveLatestVersions(List(input), 1)

    assertEquals(result, List(exactDep("org.typelevel", "cats-core", "2.9.0")))
  }

  test("resolveLatestVersions handles multiple dependencies") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.typelevel", "cats-core") -> List("2.9.0", "2.10.0"),
        ("org.http4s", "http4s-core")  -> List("0.23.25", "0.23.30"),
        ("io.circe", "circe-core")     -> List("0.14.7", "0.14.10")
      )
    )

    val input = List(
      dep("org.typelevel", "cats-core", "2.9.0"),
      dep("org.http4s", "http4s-core", "0.23.25"),
      dep("io.circe", "circe-core", "0.14.7")
    )

    val result = Utils.resolveLatestVersions(input, 2)

    val expected = List(
      dep("org.typelevel", "cats-core", "2.10.0"),
      dep("org.http4s", "http4s-core", "0.23.30"),
      dep("io.circe", "circe-core", "0.14.10")
    )

    assertEquals(result, expected)
  }

  test("resolveLatestVersions logs update symbol for updated dependency") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(("org.typelevel", "cats-core") -> List("2.9.0", "2.10.0"))
    )

    Utils.resolveLatestVersions(List(dep("org.typelevel", "cats-core", "2.9.0")), 1)

    val expected = List(s" ↳ $YELLOW⬆$RESET ${YELLOW}org.typelevel::cats-core:2.9.0$RESET -> ${CYAN}2.10.0$RESET")

    assertEquals(logger.getLogs(Level.Info), expected)
  }

  test("resolveLatestVersions logs checkmark for up-to-date dependency") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(("org.typelevel", "cats-core") -> List("2.10.0"))
    )

    Utils.resolveLatestVersions(List(dep("org.typelevel", "cats-core", "2.10.0")), 1)

    val expected = List(s" ↳ $GREEN✓$RESET ${GREEN}org.typelevel::cats-core:2.10.0$RESET")

    assertEquals(logger.getLogs(Level.Info), expected)
  }

  test("resolveLatestVersions logs pinned symbol for exact-marker dependency") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(("org.typelevel", "cats-core") -> List("2.9.0", "2.10.0"))
    )

    Utils.resolveLatestVersions(List(exactDep("org.typelevel", "cats-core", "2.9.0")), 1)

    val expected = List(s" ↳ $CYAN⊙$RESET ${CYAN}org.typelevel::cats-core:=2.9.0$RESET")

    assertEquals(logger.getLogs(Level.Info), expected)
  }

  test("resolveLatestVersions follows artifact migration") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.old", "old-lib") -> List("1.0.0"),
        ("org.new", "new-lib") -> List("1.0.0", "2.0.0")
      )
    )

    implicit val migrationFinder: MigrationFinder = dep =>
      if (dep.organization === "org.old" && dep.name === "old-lib")
        Some(ArtifactMigration(Some("org.old"), "org.new", Some("old-lib"), "new-lib"))
      else None

    val result   = Utils.resolveLatestVersions(List(dep("org.old", "old-lib", "1.0.0")), 1)
    val expected = List(dep("org.new", "new-lib", "2.0.0"))

    assertEquals(result, expected)
  }

  test("resolveLatestVersions logs migration symbol for migrated dependency") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("org.old", "old-lib") -> List("1.0.0"),
        ("org.new", "new-lib") -> List("1.0.0", "2.0.0")
      )
    )

    implicit val migrationFinder: MigrationFinder = dep =>
      if (dep.organization === "org.old" && dep.name === "old-lib")
        Some(ArtifactMigration(Some("org.old"), "org.new", Some("old-lib"), "new-lib"))
      else None

    Utils.resolveLatestVersions(List(dep("org.old", "old-lib", "1.0.0")), 1)

    val expected =
      List(s" ↳ $YELLOW⇄$RESET ${YELLOW}org.old::old-lib:1.0.0$RESET -> ${CYAN}org.new::new-lib:2.0.0$RESET")

    assertEquals(logger.getLogs(Level.Info), expected)
  }

  test("resolveLatestVersions handles variable version dependency") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(("org.typelevel", "cats-core") -> List("2.9.0", "2.10.0"))
    )

    val variable = Version.Variable("catsVersion", nv("2.9.0"))
    val input    = Dependency.WithVariableVersion("org.typelevel", "cats-core", variable, isCross = true)

    val result = Utils.resolveLatestVersions(List(input), 1)

    assertEquals(result, List(input))
  }

  test("resolveLatestVersions preserves order of input dependencies") {
    implicit val versionFinder: VersionFinder = mockVersionFinder(
      Map(
        ("com.z", "z-lib") -> List("1.0.0", "2.0.0"),
        ("com.a", "a-lib") -> List("3.0.0", "4.0.0"),
        ("com.m", "m-lib") -> List("5.0.0", "6.0.0")
      )
    )

    val input = List(
      dep("com.z", "z-lib", "1.0.0", isCross = false),
      dep("com.a", "a-lib", "3.0.0", isCross = false),
      dep("com.m", "m-lib", "5.0.0", isCross = false)
    )

    val result = Utils.resolveLatestVersions(input, 2)

    val expected = List(
      dep("com.z", "z-lib", "2.0.0", isCross = false),
      dep("com.a", "a-lib", "4.0.0", isCross = false),
      dep("com.m", "m-lib", "6.0.0", isCross = false)
    )

    assertEquals(result, expected)
  }

}
