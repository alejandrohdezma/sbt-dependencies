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

package com.alejandrohdezma.sbt.dependencies.bom

/** One `<dependencyManagement>` entry. `isImport` marks a `<scope>import</scope>` BOM, recursed into rather than
  * emitted.
  */
private[bom] case class Entry(coords: Coords, isImport: Boolean) {

  /** This entry with its coordinate's placeholders expanded against `properties`; `None` if any can't be resolved. */
  def resolve(properties: Map[String, String]): Option[Entry] =
    coords.resolve(properties).map(resolved => copy(coords = resolved))

}
