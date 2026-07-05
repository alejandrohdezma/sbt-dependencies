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

/** Constants that depend on the sbt axis this plugin is built for. This is the sbt 1.x (Scala 2.12) side. */
private[dependencies] object PluginCompat {

  /** Maven artifact-name suffix of plugins targeting the running sbt. */
  val sbtPluginArtifactSuffix: String = "_2.12_1.0"

  /** Ivy-style extra attributes under which pre-Maven-layout sbt 1 plugins are published. */
  val sbtPluginAttributes: Option[Map[String, String]] = Some(Map("scalaVersion" -> "2.12", "sbtVersion" -> "1.0"))

  /** Scala version assumed for the meta-build when resolving `sbt-build` group dependencies. */
  val metaBuildScalaVersion: String = "2.12.0"

}
