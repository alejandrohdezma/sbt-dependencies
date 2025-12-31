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

import sbt._

class Keys {

  val dependenciesFromFile = settingKey[List[Dependency]]("Dependencies read from the file `project/dependencies`")

  val updateDependencies = inputKey[Unit]("Update dependencies to their latest versions")

  val inheritedDependencies = settingKey[Seq[ModuleID]]("Inherited dependencies from other projects")

  val install = inputKey[Unit]("Add new dependencies")

  val showLibraryDependencies = taskKey[Unit]("Show the library dependencies for the project")

}

object Keys extends Keys
