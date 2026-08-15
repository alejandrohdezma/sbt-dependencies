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

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** File type for `dependencies.conf`. Instantiated reflectively by the platform from the `fileType` extension point,
  * which binds it to the exact `dependencies.conf` file name.
  */
final class SbtDependenciesFileType extends LanguageFileType(SbtDependenciesLanguage) {

  /** The unique file type name, referenced by the `fileType` extension point in `plugin.xml`. */
  override def getName: String = "sbt-dependencies"

  /** Human-readable description shown in `Settings > Editor > File Types`. */
  override def getDescription: String = "sbt-dependencies configuration file"

  /** Default extension (`conf`), although the file type is bound by exact file name rather than extension. */
  override def getDefaultExtension: String = "conf"

  /** The sbt-dependencies logo shown next to `dependencies.conf` files in the project tree and editor tabs. */
  override def getIcon: Icon = SbtDependenciesIcons.File

}

/** Icons used by the plugin, loaded from the bundled `/icons` resources. */
object SbtDependenciesIcons {

  /** The sbt-dependencies logo, used as the file icon and the structure view root icon. */
  val File: Icon = IconLoader.getIcon("/icons/sbt-dependencies.svg", classOf[SbtDependenciesFileType])

}
