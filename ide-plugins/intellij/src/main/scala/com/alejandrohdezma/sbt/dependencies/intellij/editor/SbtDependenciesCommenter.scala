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

package com.alejandrohdezma.sbt.dependencies.intellij.editor

import com.intellij.lang.Commenter

/** Comment/uncomment support: `//` line comments and `/* */` block comments, matching what the HOCON parser accepts
  * (`#` also works in files but `//` is what the comment action inserts).
  */
final class SbtDependenciesCommenter extends Commenter {

  /** Prefix inserted by the "Comment with Line Comment" action. */
  override def getLineCommentPrefix: String = "//"

  /** Opening delimiter inserted by the "Comment with Block Comment" action. */
  override def getBlockCommentPrefix: String = "/*"

  /** Closing delimiter inserted by the "Comment with Block Comment" action. */
  override def getBlockCommentSuffix: String = "*/"

  /** No special prefix for commenting-out already-commented blocks. */
  override def getCommentedBlockCommentPrefix: String = null

  /** No special suffix for commenting-out already-commented blocks. */
  override def getCommentedBlockCommentSuffix: String = null

}
