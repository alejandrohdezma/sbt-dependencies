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

package com.alejandrohdezma.sbt.dependencies.intellij.lang

import com.intellij.lang.Language

/** The `dependencies.conf` language. Registered on the exact file name (see `plugin.xml`), so it never competes with
  * other plugins claiming the `.conf` extension (e.g. the HOCON plugin).
  *
  * A class with a singleton instead of an `object` extending [[Language]]: the compiler would emit static forwarders
  * for every inherited platform method, which become binary incompatibilities in the plugin verifier when the platform
  * hierarchy changes (`Language` stopped extending `AtomicReference` in 2025.3).
  */
final class SbtDependenciesLanguage private () extends Language("sbt-dependencies")

object SbtDependenciesLanguage {

  /** The singleton instance, created once so the language registers itself a single time. */
  val Instance: SbtDependenciesLanguage = new SbtDependenciesLanguage

}
