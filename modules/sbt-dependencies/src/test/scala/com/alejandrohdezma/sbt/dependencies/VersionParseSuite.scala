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

import com.alejandrohdezma.sbt.dependencies.Dependency.Version
import com.alejandrohdezma.sbt.dependencies.Dependency.Version.Numeric.Marker

class VersionParseSuite extends munit.FunSuite {

  test("parse standard Version") {
    val result = Version.unapply("1.2.3")

    val expected = Version.Numeric(List(1, 2, 3), None, Marker.NoMarker)

    assertEquals(result, Some(expected))
  }

  test("parse two-part version") {
    val result = Version.unapply("1.0")

    val expected = Version.Numeric(List(1, 0), None, Marker.NoMarker)

    assertEquals(result, Some(expected))
  }

  test("parse four-part version") {
    val result = Version.unapply("3.2.14.0")

    val expected = Version.Numeric(List(3, 2, 14, 0), None, Marker.NoMarker)

    assertEquals(result, Some(expected))
  }

  test("parse version with dot-suffix") {
    val result = Version.unapply("4.2.7.Final")

    val expected = Version.Numeric(List(4, 2, 7), Some(".Final"), Marker.NoMarker)

    assertEquals(result, Some(expected))
  }

  test("parse version with hyphen-suffix") {
    val result = Version.unapply("1.0.0-rc1")

    val expected = Version.Numeric(List(1, 0, 0), Some("-rc1"), Marker.NoMarker)

    assertEquals(result, Some(expected))
  }

  test("parse exact marker") {
    val result = Version.unapply("=1.2.3")

    val expected = Version.Numeric(List(1, 2, 3), None, Marker.Exact)

    assertEquals(result, Some(expected))
  }

  test("parse major marker") {
    val result = Version.unapply("^1.2.3")

    val expected = Version.Numeric(List(1, 2, 3), None, Marker.Major)

    assertEquals(result, Some(expected))
  }

  test("parse minor marker") {
    val result = Version.unapply("~1.2.3")

    val expected = Version.Numeric(List(1, 2, 3), None, Marker.Minor)

    assertEquals(result, Some(expected))
  }

}
