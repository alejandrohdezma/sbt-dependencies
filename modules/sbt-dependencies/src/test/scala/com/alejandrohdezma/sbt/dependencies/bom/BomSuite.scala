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

package com.alejandrohdezma.sbt.dependencies.bom

import sbt._
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.TestLogger
import com.alejandrohdezma.sbt.dependencies.bom.Bom._

class BomSuite extends munit.FunSuite {

  implicit val logger: Logger = TestLogger()

  val pins = Seq(
    ModuleID("com.fasterxml.jackson.core", "jackson-databind", "2.18.2"),
    ModuleID("org.typelevel", "cats-core_2.13", "2.10.0")
  )

  val bom = new Bom(pins, "2.13")

  test("version resolves a plain (Java) artifact against the exact name") {
    assertEquals(bom.version("com.fasterxml.jackson.core" % "jackson-databind"), "2.18.2")
  }

  test("version resolves a cross-compiled artifact against the Scala-suffixed name") {
    assertEquals(bom.version("org.typelevel" %% "cats-core"), "2.10.0")
  }

  test("version fails the build when the BOM doesn't manage the artifact") {
    val error = intercept[Exception](bom.version("com.unknown" % "thing"))

    assert(error.getMessage.contains("is not managed by the BOM"), error.getMessage)
  }

  test("dependencyOverrides keeps the first pin for each module") {
    val duplicated = Seq(
      ModuleID("com.fasterxml.jackson.core", "jackson-databind", "2.18.2"),
      ModuleID("com.fasterxml.jackson.core", "jackson-databind", "2.16.0")
    )

    assertEquals(new Bom(duplicated, "2.13").dependencyOverrides, Seq(duplicated.head))
  }

  test("the `%` syntax stamps the BOM-managed version onto an artifact") {
    val moduleID = ("org.typelevel" %% "cats-core") % bom

    assertEquals(moduleID.name, "cats-core")
    assertEquals(moduleID.revision, "2.10.0")
    assertEquals(moduleID.crossVersion, CrossVersion.binary: CrossVersion)
  }

}
