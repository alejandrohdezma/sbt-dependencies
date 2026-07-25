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
class EntrySuite extends munit.FunSuite {

  test("Entry.resolve resolves the coordinate and keeps the import flag") {
    val entry = Entry(Coords("io.netty", "netty-bom", "${netty.version}"), isImport = true)

    val resolved = entry.resolve(Map("netty.version" -> "4.1.100.Final"))

    assertEquals(resolved, Some(Entry(Coords("io.netty", "netty-bom", "4.1.100.Final"), isImport = true)))
  }

  test("Entry.resolve returns None when the coordinate can't be resolved") {
    val entry = Entry(Coords("io.netty", "netty-bom", "${netty.version}"), isImport = false)

    assertEquals(entry.resolve(Map.empty), None)
  }

}
