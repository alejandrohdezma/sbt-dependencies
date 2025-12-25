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
import com.alejandrohdezma.sbt.dependencies.Dependency.Version.Marker

class VersionCandidateSuite extends munit.FunSuite {

  test("candidate must have same shape") {
    val current        = Version(List(1, 2, 3), None, Marker.NoMarker)
    val sameShape      = Version(List(2, 0, 0), None, Marker.NoMarker)
    val differentShape = Version(List(2, 0), None, Marker.NoMarker)

    assertEquals(current.isValidCandidate(sameShape), true)
    assertEquals(current.isValidCandidate(differentShape), false)
  }

  test("candidate must have same suffix type") {
    val rc       = Version(List(1, 0, 0), Some("-rc1"), Marker.NoMarker)
    val rc2      = Version(List(1, 0, 0), Some("-rc2"), Marker.NoMarker)
    val noSuffix = Version(List(1, 0, 0), None, Marker.NoMarker)

    assertEquals(rc.isValidCandidate(rc2), true)
    assertEquals(rc.isValidCandidate(noSuffix), false)
  }

  test("exact marker rejects all candidates") {
    val exact     = Version(List(1, 2, 3), None, Marker.Exact)
    val candidate = Version(List(1, 2, 4), None, Marker.NoMarker)

    assertEquals(exact.isValidCandidate(candidate), false)
  }

  test("major marker filters by major version") {
    val major          = Version(List(1, 2, 3), None, Marker.Major)
    val sameMajor      = Version(List(1, 9, 0), None, Marker.NoMarker)
    val differentMajor = Version(List(2, 0, 0), None, Marker.NoMarker)

    assertEquals(major.isValidCandidate(sameMajor), true)
    assertEquals(major.isValidCandidate(differentMajor), false)
  }

  test("minor marker filters by major and minor") {
    val minor          = Version(List(1, 2, 3), None, Marker.Minor)
    val sameMinor      = Version(List(1, 2, 9), None, Marker.NoMarker)
    val differentMinor = Version(List(1, 3, 0), None, Marker.NoMarker)

    assertEquals(minor.isValidCandidate(sameMinor), true)
    assertEquals(minor.isValidCandidate(differentMinor), false)
  }

}
