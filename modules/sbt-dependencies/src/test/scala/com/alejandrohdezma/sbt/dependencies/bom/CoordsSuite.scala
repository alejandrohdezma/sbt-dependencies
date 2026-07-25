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

import scala.annotation.nowarn

@nowarn("msg=detected an interpolated expression")
class CoordsSuite extends munit.FunSuite {

  test("Coords.resolve expands placeholders in every field") {
    val coords = Coords("org.typelevel", "cats-core_${scala.compat.version}", "${cats.version}")

    val resolved = coords.resolve(Map("scala.compat.version" -> "2.13", "cats.version" -> "2.13.0"))

    assertEquals(resolved, Some(Coords("org.typelevel", "cats-core_2.13", "2.13.0")))
  }

  test("Coords.resolve expands placeholders recursively") {
    val coords = Coords("com.example", "library", "${a}")

    assertEquals(coords.resolve(Map("a" -> "${b}", "b" -> "1.0")), Some(Coords("com.example", "library", "1.0")))
  }

  test("Coords.resolve leaves placeholder-free coordinates untouched") {
    val coords = Coords("com.example", "library", "1.0.0")

    assertEquals(coords.resolve(Map.empty), Some(coords))
  }

  test("Coords.resolve returns None when a placeholder has no matching property") {
    val coords = Coords("com.example", "library", "${undefined}")

    assertEquals(coords.resolve(Map.empty), None)
  }

  test("Coords.resolve returns None when expansion never converges") {
    val coords = Coords("com.example", "library", "${a}")

    assertEquals(coords.resolve(Map("a" -> "${a}")), None)
  }

}
