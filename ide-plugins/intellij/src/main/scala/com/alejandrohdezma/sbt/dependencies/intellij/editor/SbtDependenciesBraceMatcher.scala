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

import com.alejandrohdezma.sbt.dependencies.intellij.lang.SbtDependenciesTokens._
import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

/** Highlights matching `{}` (groups, object entries) and `[]` (dependency arrays) pairs. */
final class SbtDependenciesBraceMatcher extends PairedBraceMatcher {

  /** The `{`/`}` and `[`/`]` pairs, both structural (they delimit groups and dependency arrays). */
  override val getPairs: Array[BracePair] = Array(
    new BracePair(LBRACE, RBRACE, true),
    new BracePair(LBRACKET, RBRACKET, true)
  )

  /** Auto-inserting a closing brace is fine before any token in this language. */
  override def isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType): Boolean = true

  /** The construct a brace belongs to starts at the brace itself (no enclosing header to jump to). */
  override def getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset

}
