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

/** The Scala version a resolver pipeline is bound to (e.g. `"2.13.16"`). Carried in `Finders` so callers can compute
  * the resolved Maven artifact name via `VersionFinder.mavenArtifactName(name, scalaVersion.value)(configuration,
  * crossVersion)`. Wrapped in a value class to avoid colliding with arbitrary `String` implicits.
  */
final case class ScalaVersion(value: String) extends AnyVal
