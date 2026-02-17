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

package com.alejandrohdezma.sbt.dependencies.constraints

class VersionPatternSuite extends munit.FunSuite {

  test("empty pattern matches everything") {
    val pattern = VersionPattern()

    assert(pattern.matches("1.0.0"))
    assert(pattern.matches("anything"))
    assert(pattern.matches(""))
  }

  test("exact matches only the exact version") {
    val pattern = VersionPattern(exact = Some("3.8.2"))

    assert(pattern.matches("3.8.2"))
    assert(!pattern.matches("3.8.1"))
    assert(!pattern.matches("3.8.20"))
  }

  test("prefix matches versions starting with the prefix") {
    val pattern = VersionPattern(prefix = Some("2.13."))

    assert(pattern.matches("2.13.0"))
    assert(pattern.matches("2.13.14"))
    assert(!pattern.matches("2.12.0"))
    assert(!pattern.matches("3.0.0"))
  }

  test("suffix matches versions ending with the suffix") {
    val pattern = VersionPattern(suffix = Some("-M1"))

    assert(pattern.matches("3.0.0-M1"))
    assert(pattern.matches("1.0-M1"))
    assert(!pattern.matches("3.0.0-M2"))
    assert(!pattern.matches("3.0.0"))
  }

  test("contains matches versions containing the substring") {
    val pattern = VersionPattern(contains = Some("rc"))

    assert(pattern.matches("1.0.0-rc1"))
    assert(pattern.matches("2.0-rc-3"))
    assert(!pattern.matches("1.0.0"))
    assert(!pattern.matches("1.0.0-beta"))
  }

  test("multiple conditions are AND-ed") {
    val pattern = VersionPattern(prefix = Some("2."), contains = Some("rc"))

    assert(pattern.matches("2.0.0-rc1"))
    assert(!pattern.matches("3.0.0-rc1"))
    assert(!pattern.matches("2.0.0"))
  }

  test("failing condition returns false") {
    val pattern = VersionPattern(exact = Some("1.0.0"), prefix = Some("2."))

    assert(!pattern.matches("1.0.0"))
    assert(!pattern.matches("2.0.0"))
  }

}
