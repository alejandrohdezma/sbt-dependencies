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

/** The field and setting vocabulary of `dependencies.conf` — the single home for these names, shared by every reader of
  * the format (the canonical `GroupConfig` model, the positioned `DependenciesDocument` view and the IDE lexers).
  */
object Fields {

  /** The dependency line of an object entry. */
  val Dependency = "dependency"

  /** The free-form note annotation of an object entry. */
  val Note = "note"

  /** The intransitive flag of an object entry. */
  val Intransitive = "intransitive"

  /** The Scala-binary-version filter annotation of an object entry. */
  val ScalaFilter = "scala-filter"

  /** The cross-version annotation of an object entry. */
  val CrossVersion = "cross-version"

  /** The overrides flag of an object entry: force the entry's version (or every pin of a `:bom` entry) across the whole
    * dependency graph through sbt's `dependencyOverrides`.
    */
  val Overrides = "overrides"

  /** The keys an object entry can declare. */
  val EntryFields: List[String] = List(Dependency, Note, Intransitive, Overrides, ScalaFilter, CrossVersion)

  /** The single-Scala-version setting of an advanced group. */
  val ScalaVersion = "scala-version"

  /** The multiple-Scala-versions setting of an advanced group. */
  val ScalaVersions = "scala-versions"

  /** The Java target version setting of an advanced group. */
  val JavaVersion = "java-version"

  /** The dependency array of an advanced group. */
  val Dependencies = "dependencies"

  /** The keys an advanced group block can declare. */
  val GroupSettings: List[String] = List(ScalaVersion, ScalaVersions, JavaVersion, Dependencies)

}
