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

/** A type class used to determine equality between 2 instances of the same type. Any 2 instances `x` and `y` are equal
  * if `eqv(x, y)` is `true`. Moreover, `eqv` should form an equivalence relation.
  */
trait Eq[A] {

  /** Returns `true` if `x` and `y` are equivalent, `false` otherwise. */
  def eqv(a: A, b: A): Boolean

  /** Returns `false` if `x` and `y` are equivalent, `true` otherwise. */
  def neqv(a: A, b: A): Boolean = !eqv(a, b)

}

@SuppressWarnings(Array("scalafix:DisableSyntax.=="))
object Eq {

  implicit class EqOps[A: Eq](a: A) {

    /** Returns `true` if `a` and `b` are equivalent, `false` otherwise. */
    def ===(b: A): Boolean = implicitly[Eq[A]].eqv(a, b)

    /** Returns `false` if `a` and `b` are equivalent, `true` otherwise. */
    def !==(b: A): Boolean = implicitly[Eq[A]].neqv(a, b)

  }

  implicit val StringEq: Eq[String] = (a, b) => a == b

  implicit val IntEq: Eq[Int] = (a, b) => a == b

  implicit val BooleanEq: Eq[Boolean] = (a, b) => a == b

  implicit def OptionEq[A: Eq]: Eq[Option[A]] = {
    case (Some(a), Some(b)) => a === b
    case (None, None)       => true
    case _                  => false
  }

  implicit def ListEq[A: Eq]: Eq[List[A]] = {
    case (Nil, Nil)                    => true
    case (x :: xs, y :: ys) if x === y => xs === ys
    case _                             => false
  }

}
