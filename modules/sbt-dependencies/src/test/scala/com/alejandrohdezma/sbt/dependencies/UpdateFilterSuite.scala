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

class UpdateFilterSuite extends munit.FunSuite {

  val dep = Dependency(
    organization = "org.typelevel",
    name = "cats-core",
    version = Version(List(0, 1, 0), None, Version.Marker.NoMarker),
    isCross = true,
    group = "test"
  )

  test("All matches everything") {
    assertEquals(UpdateFilter.All.matches(dep), true)
  }

  test("ByOrg matches by organization") {
    assertEquals(UpdateFilter.ByOrg("org.typelevel").matches(dep), true)
    assertEquals(UpdateFilter.ByOrg("com.google").matches(dep), false)
  }

  test("ByArtifact matches by artifact") {
    assertEquals(UpdateFilter.ByArtifact("cats-core").matches(dep), true)
    assertEquals(UpdateFilter.ByArtifact("cats-effect").matches(dep), false)
  }

  test("ByOrgAndArtifact matches both") {
    assertEquals(UpdateFilter.ByOrgAndArtifact("org.typelevel", "cats-core").matches(dep), true)
    assertEquals(UpdateFilter.ByOrgAndArtifact("org.typelevel", "cats-effect").matches(dep), false)
    assertEquals(UpdateFilter.ByOrgAndArtifact("com.google", "cats-core").matches(dep), false)
  }

  // --- show tests ---

  test("All.show returns 'all'") {
    assertEquals(UpdateFilter.All.show, "all")
  }

  test("ByOrg.show returns organization") {
    assertEquals(UpdateFilter.ByOrg("org.typelevel").show, "org.typelevel")
  }

  test("ByArtifact.show returns artifact") {
    assertEquals(UpdateFilter.ByArtifact("cats-core").show, "cats-core")
  }

  test("ByOrgAndArtifact.show returns org:artifact") {
    assertEquals(UpdateFilter.ByOrgAndArtifact("org.typelevel", "cats-core").show, "org.typelevel:cats-core")
  }

}
