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

import com.intellij.psi.tree.IElementType

/** Element types produced by [[SbtDependenciesLexer]] (document-level tokens) and [[DependencyStringLexer]]
  * (fine-grained tokens inside a quoted dependency string).
  */
object SbtDependenciesTokens {

  /** An element type of the `dependencies.conf` language. */
  final class Token(debugName: String) extends IElementType(debugName, SbtDependenciesLanguage)

  /** A `#`, `//` or `/* */` comment. */
  val COMMENT = new Token("COMMENT")

  /** A group name at the top level, before `= [` or `{`. */
  val GROUP_NAME = new Token("GROUP_NAME")

  /** A known setting key inside an advanced group block (`scala-version`, `java-version`, `dependencies`...). */
  val SETTING_KEY = new Token("SETTING_KEY")

  /** A known key inside an object dependency entry (`dependency`, `note`, `intransitive`...). */
  val OBJECT_KEY = new Token("OBJECT_KEY")

  /** A quoted string holding a dependency line. Split into fine-grained tokens by [[DependencyStringLexer]]. */
  val DEP_STRING = new Token("DEP_STRING")

  /** A quoted string that is not a dependency line (setting values, notes, scala filters...). */
  val STRING = new Token("STRING")

  /** A bare `true`/`false` literal inside an object entry. */
  val KEYWORD = new Token("KEYWORD")

  /** An opening `{` starting a group block or an object entry. */
  val LBRACE = new Token("LBRACE")

  /** A closing `}` ending a group block or an object entry. */
  val RBRACE = new Token("RBRACE")

  /** An opening `[` starting a dependency or setting array. */
  val LBRACKET = new Token("LBRACKET")

  /** A closing `]` ending a dependency or setting array. */
  val RBRACKET = new Token("RBRACKET")

  /** A `=` sign between a key and its value. */
  val EQ = new Token("EQ")

  /** A `,` separating array or object entries. */
  val COMMA = new Token("COMMA")

  /** Any character or word the lexer can't classify further; rendered with default text attributes. */
  val TEXT = new Token("TEXT")

  // Tokens produced by DependencyStringLexer inside DEP_STRING fragments.

  /** The opening or closing `"` of a dependency string. */
  val QUOTE = new Token("QUOTE")

  /** The organization part of a dependency line (`org.typelevel` in `org.typelevel::cats-core:2.0.0`). */
  val ORGANIZATION = new Token("ORGANIZATION")

  /** The `:` or `::` separator between organization and artifact, encoding the cross-version. */
  val SEPARATOR = new Token("SEPARATOR")

  /** The artifact name of a dependency line (`cats-core` in `org.typelevel::cats-core:2.0.0`). */
  val ARTIFACT = new Token("ARTIFACT")

  /** A plain `:` between artifact, version and configuration parts. */
  val COLON = new Token("COLON")

  /** A version-constraint prefix (`=`, `^` or `~`) preceding the version number. */
  val VERSION_MARKER = new Token("VERSION_MARKER")

  /** The version number of a dependency line, without any constraint marker. */
  val VERSION = new Token("VERSION")

  /** The `*` version placeholder meaning "resolved from the BOM". */
  val BOM_STAR = new Token("BOM_STAR")

  /** A `{{variable}}` version placeholder resolved by the SBT plugin. */
  val VARIABLE = new Token("VARIABLE")

  /** The trailing configuration of a dependency line (`test`, `bom`, `sbt-plugin`...). */
  val CONFIGURATION = new Token("CONFIGURATION")

}
