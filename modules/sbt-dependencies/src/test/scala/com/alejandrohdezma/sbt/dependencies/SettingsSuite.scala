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

import sbt._

class SettingsSuite extends munit.FunSuite {

  test("overridesFrom keeps the first flagged entry per module") {
    val flagged = Seq(
      ModuleID("org.typelevel", "cats-core_2.13", "2.10.0"),
      ModuleID("org.typelevel", "cats-core_2.13", "2.9.0"),
      ModuleID("com.typesafe", "config", "1.4.3")
    )

    val expected = Seq(
      ModuleID("org.typelevel", "cats-core_2.13", "2.10.0"),
      ModuleID("com.typesafe", "config", "1.4.3")
    )

    assertEquals(Settings.overridesFrom(flagged, Nil), expected)
  }

  test("overridesFrom drops the modules the project declares without the flag") {
    val flagged =
      Seq(ModuleID("org.typelevel", "cats-core_2.13", "2.10.0"), ModuleID("com.typesafe", "config", "1.4.3"))
    val declared = Seq(ModuleID("org.typelevel", "cats-core_2.13", "2.9.0").withConfigurations(Some("test")))

    assertEquals(Settings.overridesFrom(flagged, declared), Seq(ModuleID("com.typesafe", "config", "1.4.3")))
  }

  test("overridesFrom strips configurations") {
    val flagged = Seq(ModuleID("com.typesafe", "config", "1.4.3").withConfigurations(Some("test")))

    assertEquals(Settings.overridesFrom(flagged, Nil), Seq(ModuleID("com.typesafe", "config", "1.4.3")))
  }

}
