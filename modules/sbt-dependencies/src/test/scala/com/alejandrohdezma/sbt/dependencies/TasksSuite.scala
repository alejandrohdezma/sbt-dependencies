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

class TasksSuite extends munit.FunSuite {

  val declared = Seq(
    ModuleID("com.typesafe.akka", "akka-http_2.13", "10.1.15"),
    ModuleID("org.typelevel", "cats-core_2.13", "2.10.0"),
    ModuleID("io.circe", "circe-core_2.13", "0.14.6")
  )

  test("revisionChanges reports a declared module that resolved to another revision") {
    val resolved = Seq(
      ModuleID("com.typesafe.akka", "akka-http_2.13", "10.2.0"),
      ModuleID("org.typelevel", "cats-core_2.13", "2.10.0")
    )

    assertEquals(Tasks.revisionChanges(declared, resolved), Seq(declared.head -> "10.2.0"))
  }

  test("revisionChanges ignores modules resolved at the declared revision or absent from the resolution") {
    val resolved = Seq(ModuleID("org.typelevel", "cats-core_2.13", "2.10.0"))

    assertEquals(Tasks.revisionChanges(declared, resolved), Nil)
  }

  test("revisionChanges uses the first resolved entry for a module") {
    val resolved = Seq(
      ModuleID("com.typesafe.akka", "akka-http_2.13", "10.1.15"),
      ModuleID("com.typesafe.akka", "akka-http_2.13", "10.2.0")
    )

    assertEquals(Tasks.revisionChanges(declared, resolved), Nil)
  }

}
