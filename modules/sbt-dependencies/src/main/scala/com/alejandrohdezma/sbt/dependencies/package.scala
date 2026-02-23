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

package com.alejandrohdezma.sbt

import scala.util.Failure
import scala.util.Try

package object dependencies {

  implicit class EitherStringOps[A](either: Either[String, A]) {

    /** Runs a side effect when the value inside the `Either` is a left. */
    def onLeft(f: String => Unit): Either[String, A] = either match {
      case Left(value) =>
        f(value)
        either
      case Right(_) =>
        either
    }

  }

  implicit class TryOps[A](tryA: Try[A]) {

    /** Runs a side effect when the value inside the `Try` is a failure. */
    def onError(f: PartialFunction[Throwable, Unit]): Try[A] = tryA.recoverWith {
      case e if f.isDefinedAt(e) =>
        f(e)
        Failure(e)
    }

  }

}
