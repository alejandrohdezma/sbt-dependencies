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

package com.alejandrohdezma.sbt.dependencies.io

import com.alejandrohdezma.sbt.dependencies.model.BomAdoption
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version
import com.alejandrohdezma.sbt.dependencies.model.Group

class BomAdoptionReportSuite extends munit.FunSuite {

  val group: Group = Group("my-project")

  def numeric(version: String): Version.Numeric = Version.Numeric.unapply(version).get

  val catsCore: Dependency =
    Dependency("org.typelevel", "cats-core", numeric("2.12.0"), crossVersion = Dependency.Cross.Binary)

  val pinnedLib: Dependency = Dependency("com.example", "lib", numeric("=1.0.0"))

  test("render lists adopted and skipped lines as bullets prefixed with the group") {
    val adoptions = List(
      BomAdoption.Adopted(catsCore.withVersion(Version.Bom(None)), catsCore, numeric("2.13.0")),
      BomAdoption.Unchanged(Dependency("com.other", "thing", numeric("1.0.0"))),
      BomAdoption.Skipped(pinnedLib, "`=` marker", numeric("1.2.0"))
    )

    assertEquals(
      BomAdoptionReport.render(group, adoptions),
      Some(
        "- `my-project`: `org.typelevel::cats-core:2.12.0` → `*` (resolves to `2.13.0`)\n" +
          "- `my-project`: `com.example:lib:=1.0.0` left as is — `=` marker (BOM pins `1.2.0`)\n"
      )
    )
  }

  test("render returns None when nothing was adopted or skipped") {
    val adoptions = List(BomAdoption.Unchanged(catsCore))

    assertEquals(BomAdoptionReport.render(group, adoptions), None)
    assertEquals(BomAdoptionReport.render(group, Nil), None)
  }

}
