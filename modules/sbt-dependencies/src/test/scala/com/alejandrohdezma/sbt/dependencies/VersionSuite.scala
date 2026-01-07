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

import com.alejandrohdezma.sbt.dependencies.Dependency.Version
import com.alejandrohdezma.sbt.dependencies.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.Dependency.Version.Numeric.Marker

class VersionSuite extends munit.FunSuite {

  // --- isStableVersion tests ---

  test("isStableVersion returns true for 3-part version without suffix") {
    val version = Version.Numeric(List(1, 2, 3), None, Marker.NoMarker)
    assert(version.isStableVersion)
  }

  test("isStableVersion returns false for 2-part version") {
    val version = Version.Numeric(List(1, 2), None, Marker.NoMarker)
    assert(!version.isStableVersion)
  }

  test("isStableVersion returns false for 4-part version") {
    val version = Version.Numeric(List(1, 2, 3, 4), None, Marker.NoMarker)
    assert(!version.isStableVersion)
  }

  test("isStableVersion returns false for version with suffix") {
    val version = Version.Numeric(List(1, 2, 3), Some("-rc1"), Marker.NoMarker)
    assert(!version.isStableVersion)
  }

  test("isStableVersion returns false for version with .Final suffix") {
    val version = Version.Numeric(List(1, 2, 3), Some(".Final"), Marker.NoMarker)
    assert(!version.isStableVersion)
  }

  // --- isSameVersion tests ---

  test("isSameVersion returns true for equal versions") {
    val v1 = Version.Numeric(List(1, 2, 3), None, Marker.NoMarker)
    val v2 = Version.Numeric(List(1, 2, 3), None, Marker.NoMarker)
    assert(v1.isSameVersion(v2))
  }

  test("isSameVersion returns true for equal versions with suffix") {
    val v1 = Version.Numeric(List(1, 2, 3), Some("-rc1"), Marker.NoMarker)
    val v2 = Version.Numeric(List(1, 2, 3), Some("-rc1"), Marker.NoMarker)
    assert(v1.isSameVersion(v2))
  }

  test("isSameVersion returns false for different parts") {
    val v1 = Version.Numeric(List(1, 2, 3), None, Marker.NoMarker)
    val v2 = Version.Numeric(List(1, 2, 4), None, Marker.NoMarker)
    assert(!v1.isSameVersion(v2))
  }

  test("isSameVersion returns false for different suffix") {
    val v1 = Version.Numeric(List(1, 2, 3), Some("-rc1"), Marker.NoMarker)
    val v2 = Version.Numeric(List(1, 2, 3), Some("-rc2"), Marker.NoMarker)
    assert(!v1.isSameVersion(v2))
  }

  test("isSameVersion ignores marker differences") {
    val v1 = Version.Numeric(List(1, 2, 3), None, Marker.NoMarker)
    val v2 = Version.Numeric(List(1, 2, 3), None, Marker.Exact)
    assert(v1.isSameVersion(v2))
  }

  // --- major tests ---

  test("major returns first part") {
    val version = Version.Numeric(List(2, 10, 0), None, Marker.NoMarker)
    assertEquals(version.major, 2)
  }

  test("major returns 0 for empty parts") {
    val version = Numeric(List.empty, None, Marker.NoMarker)
    assertEquals(version.major, 0)
  }

  // --- minor tests ---

  test("minor returns second part") {
    val version = Version.Numeric(List(2, 10, 0), None, Marker.NoMarker)
    assertEquals(version.minor, 10)
  }

  test("minor returns 0 for single-part version") {
    val version = Version.Numeric(List(2), None, Marker.NoMarker)
    assertEquals(version.minor, 0)
  }

  test("minor returns 0 for empty parts") {
    val version = Numeric(List.empty, None, Marker.NoMarker)
    assertEquals(version.minor, 0)
  }

  // --- toVersionString tests ---

  test("toVersionString formats parts without suffix") {
    val version = Version.Numeric(List(1, 2, 3), None, Marker.NoMarker)
    assertEquals(version.toVersionString, "1.2.3")
  }

  test("toVersionString includes suffix") {
    val version = Version.Numeric(List(1, 2, 3), Some("-rc1"), Marker.NoMarker)
    assertEquals(version.toVersionString, "1.2.3-rc1")
  }

  test("toVersionString does not include marker") {
    val version = Version.Numeric(List(1, 2, 3), None, Marker.Exact)
    assertEquals(version.toVersionString, "1.2.3")
  }

  // --- show tests ---

  test("show includes marker prefix") {
    val exact = Version.Numeric(List(1, 2, 3), None, Marker.Exact)
    assertEquals(exact.show, "=1.2.3")

    val major = Version.Numeric(List(1, 2, 3), None, Marker.Major)
    assertEquals(major.show, "^1.2.3")

    val minor = Version.Numeric(List(1, 2, 3), None, Marker.Minor)
    assertEquals(minor.show, "~1.2.3")
  }

  test("show with no marker has no prefix") {
    val version = Version.Numeric(List(1, 2, 3), None, Marker.NoMarker)
    assertEquals(version.show, "1.2.3")
  }

  test("show includes suffix after marker") {
    val version = Version.Numeric(List(1, 2, 3), Some("-rc1"), Marker.Exact)
    assertEquals(version.show, "=1.2.3-rc1")
  }

  // --- suffixType tests ---

  test("suffixType returns None for no suffix") {
    val version = Version.Numeric(List(1, 2, 3), None, Marker.NoMarker)
    assertEquals(version.suffixType, None)
  }

  test("suffixType extracts letters from hyphen suffix") {
    val version = Version.Numeric(List(1, 2, 3), Some("-rc1"), Marker.NoMarker)
    assertEquals(version.suffixType, Some("rc*"))
  }

  test("suffixType extracts letters from dot suffix") {
    val version = Version.Numeric(List(1, 2, 3), Some(".Final"), Marker.NoMarker)
    assertEquals(version.suffixType, Some("final"))
  }

  test("suffixType replaces numbers with asterisk") {
    val version = Version.Numeric(List(1, 2, 3), Some("-alpha2"), Marker.NoMarker)
    assertEquals(version.suffixType, Some("alpha*"))
  }

  test("suffixType handles -jre suffix") {
    val version = Version.Numeric(List(32, 1, 0), Some("-jre"), Marker.NoMarker)
    assertEquals(version.suffixType, Some("jre"))
  }

  test("suffixType handles complex suffix") {
    val version = Version.Numeric(List(1, 0, 0), Some("-M1-bin-20231010"), Marker.NoMarker)
    assertEquals(version.suffixType, Some("m*-bin-********"))
  }

  // --- suffixNumber tests ---

  test("suffixNumber returns None for no suffix") {
    val version = Version.Numeric(List(1, 2, 3), None, Marker.NoMarker)
    assertEquals(version.suffixNumber, None)
  }

  test("suffixNumber extracts number from -rc1") {
    val version = Version.Numeric(List(1, 2, 3), Some("-rc1"), Marker.NoMarker)
    assertEquals(version.suffixNumber, Some(1))
  }

  test("suffixNumber extracts number from -rc10") {
    val version = Version.Numeric(List(1, 2, 3), Some("-rc10"), Marker.NoMarker)
    assertEquals(version.suffixNumber, Some(10))
  }

  test("suffixNumber returns None for suffix without number") {
    val version = Version.Numeric(List(32, 1, 0), Some("-jre"), Marker.NoMarker)
    assertEquals(version.suffixNumber, None)
  }

  test("suffixNumber returns None for .Final") {
    val version = Version.Numeric(List(1, 2, 3), Some(".Final"), Marker.NoMarker)
    assertEquals(version.suffixNumber, None)
  }

  test("suffixNumber extracts first number from complex suffix") {
    val version = Version.Numeric(List(1, 0, 0), Some("-M2-beta"), Marker.NoMarker)
    assertEquals(version.suffixNumber, Some(2))
  }

}
