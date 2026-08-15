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

package com.alejandrohdezma.sbt.dependencies.intellij.highlight

import com.alejandrohdezma.sbt.dependencies.intellij.highlight.SbtDependenciesSyntaxHighlighter._
import com.alejandrohdezma.sbt.dependencies.intellij.lang.DependencyStringLexer
import com.alejandrohdezma.sbt.dependencies.intellij.lang.SbtDependenciesLexer
import com.alejandrohdezma.sbt.dependencies.intellij.lang.SbtDependenciesTokens._
import com.intellij.lexer.LayeredLexer
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.editor.{DefaultLanguageHighlighterColors => Colors}
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

/** Syntax highlighting for `dependencies.conf`. Layers [[DependencyStringLexer]] on top of [[SbtDependenciesLexer]] so
  * quoted dependency strings get per-part coloring (organization, artifact, version, configuration...).
  */
final class SbtDependenciesSyntaxHighlighter extends SyntaxHighlighterBase {

  /** A [[SbtDependenciesLexer]] with [[DependencyStringLexer]] layered on `DEP_STRING` tokens. */
  override def getHighlightingLexer: Lexer = {
    val lexer = new LayeredLexer(new SbtDependenciesLexer)
    lexer.registerLayer(new DependencyStringLexer, DEP_STRING)
    lexer
  }

  /** The color key for `tokenType` according to the `Attributes` map, or no highlighting if unmapped. */
  override def getTokenHighlights(tokenType: IElementType): Array[TextAttributesKey] =
    SyntaxHighlighterBase.pack(Attributes.get(tokenType).orNull)

}

/** The color keys of the language, each falling back to a standard IDE color so every theme works out of the box. Users
  * can override them from the [[SbtDependenciesColorSettingsPage]].
  */
object SbtDependenciesSyntaxHighlighter {

  /** Colors `#`, `//` and `/* */` comments. */
  val Comment = createTextAttributesKey("SBT_DEPENDENCIES_COMMENT", Colors.LINE_COMMENT)

  /** Colors group names at the top level. */
  val GroupName = createTextAttributesKey("SBT_DEPENDENCIES_GROUP_NAME", Colors.KEYWORD)

  /** Colors setting keys inside advanced group blocks (`scala-version`, `dependencies`...). */
  val SettingKey = createTextAttributesKey("SBT_DEPENDENCIES_SETTING_KEY", Colors.KEYWORD)

  /** Colors keys inside object dependency entries (`dependency`, `note`...). */
  val ObjectKey = createTextAttributesKey("SBT_DEPENDENCIES_OBJECT_KEY", Colors.INSTANCE_FIELD)

  /** Colors plain quoted strings and the quotes around dependency strings. */
  val StringValue = createTextAttributesKey("SBT_DEPENDENCIES_STRING", Colors.STRING)

  /** Colors `true`/`false` literals. */
  val Keyword = createTextAttributesKey("SBT_DEPENDENCIES_KEYWORD", Colors.KEYWORD)

  /** Colors `{` and `}`. */
  val Braces = createTextAttributesKey("SBT_DEPENDENCIES_BRACES", Colors.BRACES)

  /** Colors `[` and `]`. */
  val Brackets = createTextAttributesKey("SBT_DEPENDENCIES_BRACKETS", Colors.BRACKETS)

  /** Colors `=`, `,` and the `:`/`::` separators inside dependency strings. */
  val Operator = createTextAttributesKey("SBT_DEPENDENCIES_OPERATOR", Colors.OPERATION_SIGN)

  /** Colors the organization part of a dependency string. */
  val Organization = createTextAttributesKey("SBT_DEPENDENCIES_ORGANIZATION", Colors.STATIC_FIELD)

  /** Colors the artifact part of a dependency string. */
  val Artifact = createTextAttributesKey("SBT_DEPENDENCIES_ARTIFACT", Colors.FUNCTION_DECLARATION)

  /** Colors the `=`, `^` or `~` version-constraint marker. */
  val VersionMarker = createTextAttributesKey("SBT_DEPENDENCIES_VERSION_MARKER", Colors.KEYWORD)

  /** Colors the version number of a dependency string. */
  val VersionValue = createTextAttributesKey("SBT_DEPENDENCIES_VERSION", Colors.NUMBER)

  /** Colors the `*` BOM version placeholder. */
  val BomStar = createTextAttributesKey("SBT_DEPENDENCIES_BOM_STAR", Colors.CONSTANT)

  /** Colors `{{variable}}` version placeholders. */
  val Variable = createTextAttributesKey("SBT_DEPENDENCIES_VARIABLE", Colors.GLOBAL_VARIABLE)

  /** Colors the trailing configuration of a dependency string (`test`, `bom`...). */
  val Configuration = createTextAttributesKey("SBT_DEPENDENCIES_CONFIGURATION", Colors.METADATA)

  private val Attributes: Map[IElementType, TextAttributesKey] = Map(
    COMMENT        -> Comment,
    GROUP_NAME     -> GroupName,
    SETTING_KEY    -> SettingKey,
    OBJECT_KEY     -> ObjectKey,
    DEP_STRING     -> StringValue,
    STRING         -> StringValue,
    QUOTE          -> StringValue,
    KEYWORD        -> Keyword,
    LBRACE         -> Braces,
    RBRACE         -> Braces,
    LBRACKET       -> Brackets,
    RBRACKET       -> Brackets,
    EQ             -> Operator,
    COMMA          -> Operator,
    SEPARATOR      -> Operator,
    COLON          -> Operator,
    ORGANIZATION   -> Organization,
    ARTIFACT       -> Artifact,
    VERSION_MARKER -> VersionMarker,
    VERSION        -> VersionValue,
    BOM_STAR       -> BomStar,
    VARIABLE       -> Variable,
    CONFIGURATION  -> Configuration
  )

}

/** Factory registered under the `lang.syntaxHighlighterFactory` extension point. */
final class SbtDependenciesSyntaxHighlighterFactory extends SyntaxHighlighterFactory {

  /** A fresh [[SbtDependenciesSyntaxHighlighter]]; the highlighter is stateless so no caching is needed. */
  override def getSyntaxHighlighter(project: Project, virtualFile: VirtualFile): SbtDependenciesSyntaxHighlighter =
    new SbtDependenciesSyntaxHighlighter

}
