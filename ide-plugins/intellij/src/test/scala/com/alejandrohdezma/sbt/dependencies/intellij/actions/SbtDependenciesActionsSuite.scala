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

package com.alejandrohdezma.sbt.dependencies.intellij.actions

class SbtDependenciesActionsSuite extends munit.FunSuite {

  test("installArguments routes reserved groups to their global tasks") {
    val dependency = "org.typelevel::cats-core:2.10.0"

    assertEquals(
      SbtDependenciesActions.installArguments("sbt-build", dependency),
      List("installBuildDependencies", dependency)
    )
    assertEquals(
      SbtDependenciesActions.installArguments("common-settings", dependency),
      List("installCommonDependencies", dependency)
    )
    assertEquals(
      SbtDependenciesActions.installArguments("core", dependency),
      List("core/install", dependency)
    )
  }

}
