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

package com.alejandrohdezma.sbt.dependencies.model

import com.alejandrohdezma.sbt.dependencies.model.Eq._

class EqSuite extends munit.FunSuite {

  // --- String Eq tests ---

  test("String === returns true for equal strings") {
    assert("hello" === "hello")
  }

  test("String === returns false for different strings") {
    assert(!("hello" === "world"))
  }

  test("String !== returns true for different strings") {
    assert("hello" !== "world")
  }

  test("String !== returns false for equal strings") {
    assert(!("hello" !== "hello"))
  }

  // --- Int Eq tests ---

  test("Int === returns true for equal ints") {
    assert(42 === 42)
  }

  test("Int === returns false for different ints") {
    assert(!(42 === 43))
  }

  test("Int !== returns true for different ints") {
    assert(42 !== 43)
  }

  test("Int !== returns false for equal ints") {
    assert(!(42 !== 42))
  }

  // --- Boolean Eq tests ---

  test("Boolean === returns true for equal booleans") {
    assert(true === true)
    assert(false === false)
  }

  test("Boolean === returns false for different booleans") {
    assert(!(true === false))
  }

  test("Boolean !== returns true for different booleans") {
    assert(true !== false)
  }

  test("Boolean !== returns false for equal booleans") {
    assert(!(true !== true))
  }

  // --- Option Eq tests ---

  test("Option === returns true for equal Some values") {
    val a: Option[String] = Some("hello")
    val b: Option[String] = Some("hello")
    assert(a === b)
  }

  test("Option === returns true for None values") {
    assert(Option.empty[String] === None)
  }

  test("Option === returns false for Some vs None") {
    val a: Option[String] = Some("hello")
    val b: Option[String] = None
    assert(!(a === b))
  }

  test("Option === returns false for different Some values") {
    val a: Option[String] = Some("hello")
    val b: Option[String] = Some("world")
    assert(!(a === b))
  }

  test("Option !== returns true for Some vs None") {
    val a: Option[String] = Some("hello")
    val b: Option[String] = None
    assert(a !== b)
  }

  test("Option !== returns false for equal Some values") {
    val a: Option[String] = Some("hello")
    val b: Option[String] = Some("hello")
    assert(!(a !== b))
  }

  // --- List Eq tests ---

  test("List === returns true for equal lists") {
    assert(List(1, 2, 3) === List(1, 2, 3))
  }

  test("List === returns true for empty lists") {
    assert(List.empty[Int] === Nil)
  }

  test("List === returns false for different lists") {
    assert(!(List(1, 2, 3) === List(1, 2, 4)))
  }

  test("List === returns false for different length lists") {
    assert(!(List(1, 2) === List(1, 2, 3)))
  }

  test("List !== returns true for different lists") {
    assert(List(1, 2, 3) !== List(1, 2, 4))
  }

  test("List !== returns false for equal lists") {
    assert(!(List(1, 2, 3) !== List(1, 2, 3)))
  }

  // --- neqv method tests ---

  test("neqv returns true when eqv returns false") {
    val eq = implicitly[Eq[String]]
    assert(eq.neqv("a", "b"))
  }

  test("neqv returns false when eqv returns true") {
    val eq = implicitly[Eq[String]]
    assert(!eq.neqv("a", "a"))
  }

}
