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

/** What `update` does when an `overrides = true` pin forces a module below the version the dependency graph would
  * resolve to without it.
  */
sealed trait LoweringCheck extends Product with Serializable

object LoweringCheck {

  /** Skips the check, and the second resolution it needs. */
  case object Off extends LoweringCheck

  /** Logs a warning for every lowered module. */
  case object Warn extends LoweringCheck

  /** Fails `update`, listing every lowered module. */
  case object Error extends LoweringCheck

}
