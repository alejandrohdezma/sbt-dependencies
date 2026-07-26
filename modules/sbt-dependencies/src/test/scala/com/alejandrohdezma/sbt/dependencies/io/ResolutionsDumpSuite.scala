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

import com.alejandrohdezma.sbt.dependencies.io.ResolutionsDump._

class ResolutionsDumpSuite extends munit.FunSuite {

  test("toJson renders boms, variables and the source hash, dropping groups with nothing resolved") {
    val jacksonBom = Bom(
      "com.fasterxml.jackson",
      "jackson-bom",
      "2.17.0",
      List(Pin("com.fasterxml.jackson.core", "jackson-databind", "2.17.0"))
    )

    val projects = List(
      ProjectResolutions(
        "myproject",
        List("2.13", "3"),
        List("com.fasterxml.jackson:jackson-bom:2.17.0@2.13" -> jacksonBom),
        List(VariableResolution("org.typelevel", "cats-core", cross = true, "catsVersion", "2.13.0"))
      ),
      ProjectResolutions("empty", List("2.13"), Nil, Nil)
    )

    val expected =
      """{
        |  "version": 1,
        |  "sourceHash": "abc123",
        |  "boms": {
        |    "com.fasterxml.jackson:jackson-bom:2.17.0@2.13": {"organization": "com.fasterxml.jackson", "name": "jackson-bom", "version": "2.17.0", "entries": [{"organization": "com.fasterxml.jackson.core", "name": "jackson-databind", "version": "2.17.0"}]}
        |  },
        |  "projects": {
        |    "myproject": {"scalaBinaryVersions": ["2.13", "3"], "boms": ["com.fasterxml.jackson:jackson-bom:2.17.0@2.13"], "variables": [{"organization": "org.typelevel", "name": "cats-core", "cross": true, "variable": "catsVersion", "version": "2.13.0"}]}
        |  }
        |}
        |""".stripMargin

    assertEquals(toJson("abc123", projects), expected)
  }

  test("toJson renders empty objects when there are no boms nor projects") {
    val expected =
      """{
        |  "version": 1,
        |  "sourceHash": "hash",
        |  "boms": {},
        |  "projects": {}
        |}
        |""".stripMargin

    assertEquals(toJson("hash", Nil), expected)
  }

  test("toJson escapes quotes and backslashes in values") {
    val projects = List {
      ProjectResolutions(
        "grp",
        List("2.13"),
        Nil,
        List(VariableResolution("org", """a"b\c""", cross = false, "v", "1.0.0"))
      )
    }

    assert(toJson("h", projects).contains("""\"b\\c"""))
  }

}
