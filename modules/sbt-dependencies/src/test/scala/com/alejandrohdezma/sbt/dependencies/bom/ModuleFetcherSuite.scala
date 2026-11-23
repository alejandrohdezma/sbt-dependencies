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

import sbt._
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.TestLogger

class ModuleFetcherSuite extends munit.FunSuite {

  implicit val logger: Logger = TestLogger()

  val bom = ModuleID("com.example", "my-bom", "1.0.0")

  test("ModuleFetcher.fromCoursier serves the pom from the first Maven resolver that has it") {
    val first  = mavenRepository(bom -> "first")
    val second = mavenRepository(bom -> "second")

    val pom = ModuleFetcher.fromCoursier(Seq("first".at(first), "second".at(second))).fetch(bom)

    assertEquals(IO.read(pom), "first")
  }

  test("ModuleFetcher.fromCoursier falls through to later resolvers") {
    val empty  = mavenRepository()
    val second = mavenRepository(bom -> "second")

    val pom = ModuleFetcher.fromCoursier(Seq("empty".at(empty), "second".at(second))).fetch(bom)

    assertEquals(IO.read(pom), "second")
  }

  test("ModuleFetcher.fromCoursier reads poms from Ivy-pattern resolvers like ~/.ivy2/local") {
    val directory = IO.createTemporaryDirectory

    IO.write(directory / "com.example" / "my-bom" / "1.0.0" / "poms" / "my-bom.pom", "ivy-local")

    implicit val patterns: Patterns = Resolver.ivyStylePatterns

    val pom = ModuleFetcher.fromCoursier(Seq(Resolver.file("local", directory))).fetch(bom)

    assertEquals(IO.read(pom), "ivy-local")
  }

  test("ModuleFetcher.fromCoursier fails naming the module and every URL it tried") {
    val first  = mavenRepository()
    val second = mavenRepository()

    val error = intercept[RuntimeException] {
      ModuleFetcher.fromCoursier(Seq("first".at(first), "second".at(second))).fetch(bom)
    }

    assert(error.getMessage.startsWith(s"Failed to resolve $bom:"), error.getMessage)
    assert(error.getMessage.contains(s"${first}com/example/my-bom/1.0.0/my-bom-1.0.0.pom"), error.getMessage)
    assert(error.getMessage.contains(s"${second}com/example/my-bom/1.0.0/my-bom-1.0.0.pom"), error.getMessage)
  }

  test("ModuleFetcher.fromCoursier fails when no resolver can serve poms") {
    val error = intercept[RuntimeException](ModuleFetcher.fromCoursier(Seq.empty).fetch(bom))

    assertEquals(error.getMessage, s"Failed to resolve $bom: pom not found in any of the configured resolvers")
  }

  /** A temporary Maven repository holding the given poms, returned as its `file:` root URL. */
  def mavenRepository(poms: (ModuleID, String)*): String = {
    val directory = IO.createTemporaryDirectory

    poms.foreach { case (module, content) =>
      val path = s"${module.organization.replace('.', '/')}/${module.name}/${module.revision}"

      IO.write(directory / path / s"${module.name}-${module.revision}.pom", content)
    }

    directory.toURI.toString
  }

}
