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

import scala.Console._

import sbt.librarymanagement.CrossVersion
import sbt.librarymanagement.ModuleID
import sbt.util.Level

import com.alejandrohdezma.sbt.dependencies.TestLogger
import com.alejandrohdezma.sbt.dependencies.constraints.ArtifactMigration
import com.alejandrohdezma.sbt.dependencies.finders.Finders
import com.alejandrohdezma.sbt.dependencies.finders.MigrationFinder
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version.Numeric.Marker
import com.alejandrohdezma.sbt.dependencies.model.DependencyOps._
import com.alejandrohdezma.sbt.dependencies.model.Eq._

class DependencyOpsSuite extends munit.FunSuite {

  implicit val logger: TestLogger = TestLogger()

  override def beforeEach(context: BeforeEach): Unit = logger.cleanLogs()

  val oldLibMigration: MigrationFinder = dep =>
    if (dep.organization === "org.old" && dep.name === "old-lib")
      Some(ArtifactMigration(Some("org.old"), "org.new", Some("old-lib"), "new-lib"))
    else None

  implicit val finders: Finders = Finders.noop.withMigrationFinder(oldLibMigration)

  def bomDep(
      org: String,
      name: String,
      crossVersion: CrossVersion = CrossVersion.binary,
      configuration: String = "compile",
      note: Option[String] = None,
      intransitive: Boolean = false
  ): Dependency =
    Dependency(org, name, Version.Bom(None), configuration = configuration, note = note, intransitive = intransitive,
      crossVersion = Dependency.Cross.fromSbt(crossVersion))

  def numericDep(org: String, name: String, version: String): Dependency =
    Dependency(org, name, Version.Numeric.unapply(version).get)

  test("migrateBomManaged rewrites a `*` dependency when the new BOM pins the migrated coordinates") {
    val pins = Seq(ModuleID("org.new", "new-lib_2.13", "2.0.0"))

    val result = bomDep("org.old", "old-lib").migrateBomManaged(pins, "2.13")

    assertEquals(result, bomDep("org.new", "new-lib"))
    assertEquals(
      logger.getLogs(Level.Info),
      List(s" ↳ $YELLOW🔀$RESET ${YELLOW}org.old::old-lib:*$RESET -> ${CYAN}org.new::new-lib:*$RESET")
    )
  }

  test("migrateBomManaged matches Java dependencies against unsuffixed pins") {
    val pins = Seq(ModuleID("org.new", "new-lib", "2.0.0"))

    val result = bomDep("org.old", "old-lib", crossVersion = CrossVersion.disabled).migrateBomManaged(pins, "2.13")

    assertEquals(result, bomDep("org.new", "new-lib", crossVersion = CrossVersion.disabled))
  }

  test("migrateBomManaged preserves note, intransitive and configuration on rewrite") {
    val pins = Seq(ModuleID("org.new", "new-lib_2.13", "2.0.0"))

    val original = bomDep("org.old", "old-lib", configuration = "test", note = Some("why"), intransitive = true)

    val result = original.migrateBomManaged(pins, "2.13")

    assertEquals(result, bomDep("org.new", "new-lib", configuration = "test", note = Some("why"), intransitive = true))
  }

  test("migrateBomManaged keeps a `*` dependency when the new BOM still pins the old coordinates") {
    val pins = Seq(ModuleID("org.old", "old-lib_2.13", "1.0.0"))

    val result = bomDep("org.old", "old-lib").migrateBomManaged(pins, "2.13")

    assertEquals(result, bomDep("org.old", "old-lib"))
    assertEquals(logger.getLogs(Level.Warn), Nil)
  }

  test("migrateBomManaged keeps a `*` dependency and warns when the new BOM pins neither coordinate") {
    val pins = Seq(ModuleID("org.other", "other-lib_2.13", "1.0.0"))

    val result = bomDep("org.old", "old-lib").migrateBomManaged(pins, "2.13")

    assertEquals(result, bomDep("org.old", "old-lib"))
    assertEquals(
      logger.getLogs(Level.Warn),
      List(
        "org.old:old-lib has migrated to org.new:new-lib, but no visible BOM's new version pins either " +
          "coordinate — the `*` line was left untouched and will need manual attention"
      )
    )
  }

  test("migrateBomManaged leaves dependencies without a matching migration untouched") {
    val pins = Seq(ModuleID("org.new", "new-lib_2.13", "2.0.0"))

    val input = List(bomDep("org.other", "other-lib"), numericDep("org.old", "old-lib", "1.0.0"))

    assertEquals(input.map(_.migrateBomManaged(pins, "2.13")), input)
  }

  test("hasBomManagedMigration only flags `*` dependencies with a matching migration") {
    assert(bomDep("org.old", "old-lib").hasBomManagedMigration)
    assert(!numericDep("org.old", "old-lib", "1.0.0").hasBomManagedMigration)
    assert(!bomDep("org.other", "other-lib").hasBomManagedMigration)
  }

  test("useBomManagedVersion rewrites a pinned cross dependency to `*`") {
    val pins = Seq(ModuleID("org.lib", "lib_2.13", "1.0.0"))

    val dependency =
      Dependency("org.lib", "lib", Version.Numeric.unapply("1.0.0").get, crossVersion = Dependency.Cross.Binary)

    val result = dependency.useBomManagedVersion(pins, "2.13")

    assertEquals(result, dependency.withVersion(Version.Bom(None)))
    assertEquals(
      logger.getLogs(Level.Info),
      List(s" ↳ $YELLOW🔗$RESET ${YELLOW}org.lib::lib:1.0.0$RESET -> ${CYAN}org.lib::lib:*$RESET")
    )
  }

  test("useBomManagedVersion matches Java dependencies against unsuffixed pins") {
    val pins = Seq(ModuleID("org.lib", "lib", "1.0.0"))

    val dependency =
      Dependency("org.lib", "lib", Version.Numeric.unapply("1.0.0").get, crossVersion = Dependency.Cross.Disabled)

    assertEquals(dependency.useBomManagedVersion(pins, "2.13"), dependency.withVersion(Version.Bom(None)))
  }

  test("useBomManagedVersion notes the resolved version when the pin differs from the declared one") {
    val pins = Seq(ModuleID("org.lib", "lib_2.13", "1.2.0"))

    val dependency =
      Dependency("org.lib", "lib", Version.Numeric.unapply("1.0.0").get, crossVersion = Dependency.Cross.Binary)

    val result = dependency.useBomManagedVersion(pins, "2.13")

    assertEquals(result, dependency.withVersion(Version.Bom(None)))
    assertEquals(
      logger.getLogs(Level.Info),
      List(s" ↳ $YELLOW🔗$RESET ${YELLOW}org.lib::lib:1.0.0$RESET -> ${CYAN}org.lib::lib:*$RESET (now 1.2.0)")
    )
  }

  test("useBomManagedVersion rewrites marked versions, dropping the marker") {
    val pins = Seq(ModuleID("org.lib", "lib_2.13", "1.0.0"))

    List(Marker.Exact, Marker.Major, Marker.Minor).foreach { marker =>
      val dependency = Dependency(
        "org.lib",
        "lib",
        Version.Numeric.unapply("1.0.0").get.withMarker(marker),
        crossVersion = Dependency.Cross.Binary
      )

      assertEquals(dependency.useBomManagedVersion(pins, "2.13"), dependency.withVersion(Version.Bom(None)))
    }
  }

  test("useBomManagedVersion preserves note, intransitive and configuration on rewrite") {
    val pins = Seq(ModuleID("org.lib", "lib_2.13", "1.0.0"))

    val dependency = Dependency(
      "org.lib",
      "lib",
      Version.Numeric.unapply("1.0.0").get,
      configuration = "test",
      note = Some("why"),
      intransitive = true,
      crossVersion = Dependency.Cross.Binary
    )

    assertEquals(dependency.useBomManagedVersion(pins, "2.13"), dependency.withVersion(Version.Bom(None)))
  }

  test("useBomManagedVersion leaves unpinned dependencies untouched") {
    val pins = Seq(ModuleID("org.other", "other-lib_2.13", "1.0.0"))

    val dependency =
      Dependency("org.lib", "lib", Version.Numeric.unapply("1.0.0").get, crossVersion = Dependency.Cross.Binary)

    assertEquals(dependency.useBomManagedVersion(pins, "2.13"), dependency)
    assertEquals(logger.getLogs(Level.Info), Nil)
  }

  test("useBomManagedVersion rewrites pinned variable versions (bom > variable)") {
    val pins = Seq(ModuleID("org.lib", "lib_2.13", "1.0.0"))

    val variable = Version.Variable("libVersion", Version.Numeric.unapply("1.0.0"))

    val dependency = Dependency("org.lib", "lib", variable, crossVersion = Dependency.Cross.Binary)

    assertEquals(dependency.useBomManagedVersion(pins, "2.13"), dependency.withVersion(Version.Bom(None)))
    assertEquals(
      logger.getLogs(Level.Info),
      List(s" ↳ $YELLOW🔗$RESET $YELLOW${dependency.toLine}$RESET -> ${CYAN}org.lib::lib:*$RESET")
    )
  }

  test("useBomManagedVersion leaves already BOM-managed versions untouched") {
    val pins = Seq(ModuleID("org.lib", "lib_2.13", "1.0.0"))

    assertEquals(bomDep("org.lib", "lib").useBomManagedVersion(pins, "2.13"), bomDep("org.lib", "lib"))
  }

  test("useBomManagedVersion leaves lines where `*` would be illegal untouched") {
    val pins = Seq(ModuleID("org.lib", "lib", "1.0.0"), ModuleID("org.lib", "lib_2.13", "1.0.0"))

    val input = List(
      Dependency(
        "org.lib",
        "lib",
        Version.Numeric.unapply("1.0.0").get,
        configuration = "bom",
        crossVersion = Dependency.Cross.Disabled
      ),
      Dependency(
        "org.lib",
        "lib",
        Version.Numeric.unapply("1.0.0").get,
        configuration = "sbt-plugin",
        crossVersion = Dependency.Cross.Disabled
      ),
      Dependency("org.lib", "lib", Version.Numeric.unapply("1.0.0").get, crossVersion = Dependency.Cross.Full)
    )

    assertEquals(input.map(_.useBomManagedVersion(pins, "2.13")), input)
  }

}
