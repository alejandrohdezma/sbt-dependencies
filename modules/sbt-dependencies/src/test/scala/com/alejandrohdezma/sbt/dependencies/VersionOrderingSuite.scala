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

import scala.math.Ordering.Implicits._

import com.alejandrohdezma.sbt.dependencies.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.Dependency.Version.Numeric.Marker

class VersionOrderingSuite extends munit.FunSuite {

  private def v(parts: Int*): Numeric =
    Numeric(parts.toList, None, Marker.NoMarker)

  private def v(parts: List[Int], suffix: String): Numeric =
    Numeric(parts, Some(suffix), Marker.NoMarker)

  test("compare major versions") {
    assert(v(1, 0, 0) < v(2, 0, 0))
    assert(v(2, 0, 0) > v(1, 0, 0))
    assert(v(10, 0, 0) > v(9, 0, 0))
  }

  test("compare minor versions") {
    assert(v(1, 1, 0) < v(1, 2, 0))
    assert(v(1, 10, 0) > v(1, 9, 0))
    assert(v(1, 0, 0) < v(1, 1, 0))
  }

  test("compare patch versions") {
    assert(v(1, 0, 1) < v(1, 0, 2))
    assert(v(1, 0, 10) > v(1, 0, 9))
  }

  test("equal versions") {
    assertEquals(Ordering[Numeric].compare(v(1, 0, 0), v(1, 0, 0)), 0)
    assertEquals(Ordering[Numeric].compare(v(2, 3, 4), v(2, 3, 4)), 0)
  }

  test("two-part versions") {
    assert(v(1, 0) < v(1, 1))
    assert(v(2, 0) > v(1, 9))
  }

  test("four-part versions") {
    assert(v(1, 0, 0, 0) < v(1, 0, 0, 1))
    assert(v(3, 2, 14, 0) > v(3, 2, 13, 9))
  }

  test("different number of parts (treat missing as 0)") {
    assertEquals(Ordering[Numeric].compare(v(1, 0), v(1, 0, 0)), 0)
    assertEquals(Ordering[Numeric].compare(v(1, 0, 0), v(1, 0)), 0)
    assert(v(1, 0) < v(1, 0, 1))
    assert(v(1, 0, 1) > v(1, 0))
  }

  test("numeric suffix comparison: rc1 < rc2 < rc10") {
    assert(v(List(1, 0, 0), "-rc1") < v(List(1, 0, 0), "-rc2"))
    assert(v(List(1, 0, 0), "-rc2") < v(List(1, 0, 0), "-rc10"))
    assert(v(List(1, 0, 0), "-alpha1") < v(List(1, 0, 0), "-alpha2"))
    assert(v(List(1, 0, 0), "-M1") < v(List(1, 0, 0), "-M2"))
  }

  test("suffixes without numbers are equal") {
    assertEquals(Ordering[Numeric].compare(v(List(1, 0, 0), "-rc"), v(List(1, 0, 0), "-rc")), 0)
    assertEquals(Ordering[Numeric].compare(v(List(1, 0, 0), "-jre"), v(List(1, 0, 0), "-jre")), 0)
  }

  test("no suffix equals no suffix") {
    assertEquals(Ordering[Numeric].compare(v(1, 0, 0), v(1, 0, 0)), 0)
  }

  test("sorting versions with same suffix type") {
    val versions = List(
      v(List(1, 0, 0), "-rc3"),
      v(List(1, 0, 0), "-rc1"),
      v(List(1, 0, 0), "-rc10"),
      v(List(1, 0, 0), "-rc2")
    )

    val sorted = versions.sorted

    assertEquals(
      sorted,
      List(
        v(List(1, 0, 0), "-rc1"),
        v(List(1, 0, 0), "-rc2"),
        v(List(1, 0, 0), "-rc3"),
        v(List(1, 0, 0), "-rc10")
      )
    )
  }

  test("sorting mixed numeric versions") {
    val versions = List(
      v(2, 0, 0),
      v(1, 10, 0),
      v(1, 9, 0),
      v(1, 0, 0)
    )

    val sorted = versions.sorted

    assertEquals(
      sorted,
      List(
        v(1, 0, 0),
        v(1, 9, 0),
        v(1, 10, 0),
        v(2, 0, 0)
      )
    )
  }

  test("-jre suffix comparison by numeric parts") {
    val jre1 = v(List(32, 1, 0), "-jre")
    val jre2 = v(List(32, 1, 1), "-jre")

    assert(jre1 < jre2)
  }

  test("versions with .Final suffix compare by numeric parts") {
    assert(v(List(4, 2, 6), ".Final") < v(List(4, 2, 7), ".Final"))
    assert(v(List(4, 3, 0), ".Final") > v(List(4, 2, 7), ".Final"))
  }

}
