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

import com.alejandrohdezma.sbt.dependencies.intellij.lang.SbtDependenciesParserDefinition._
import com.alejandrohdezma.sbt.dependencies.intellij.navigation.SbtDependenciesWebLinks
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.paths.WebReference
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/** Minimal parser: the file is a flat sequence of lexer tokens. Enough PSI for structure view, formatting and
  * commenting; a structured tree can come later when references/rename need it.
  */
final class SbtDependenciesParserDefinition extends ParserDefinition {

  /** The [[SbtDependenciesLexer]] used to build the PSI tree. */
  override def createLexer(project: Project): Lexer = new SbtDependenciesLexer

  /** A parser that emits every lexer token as a direct child of the file node. */
  override def createParser(project: Project): PsiParser = new PsiParser {
    override def parse(root: IElementType, builder: PsiBuilder): ASTNode = {
      val mark = builder.mark()
      while (!builder.eof()) builder.advanceLexer()
      mark.done(root)
      builder.getTreeBuilt
    }
  }

  /** The root element type for `dependencies.conf` PSI trees. */
  override def getFileNodeType: IFileElementType = File

  /** Tokens the platform treats as comments (to-do markers, comment-aware search). */
  override def getCommentTokens: TokenSet = Comments

  /** Tokens the platform treats as string literals (spell-checking, string-aware search). */
  override def getStringLiteralElements: TokenSet = Strings

  /** Wraps any AST node in a plain PSI element; there are no specialized element classes yet. */
  override def createElement(node: ASTNode): PsiElement = new ASTWrapperPsiElement(node)

  /** The [[SbtDependenciesFile]] PSI file for a `dependencies.conf` document. */
  override def createFile(viewProvider: FileViewProvider): PsiFile = new SbtDependenciesFile(viewProvider)

}

/** Token sets consumed by the platform (comment/string classification for search, spell-checking, to-do markers). */
object SbtDependenciesParserDefinition {

  /** The root element type of `dependencies.conf` PSI trees. */
  val File = new IFileElementType(SbtDependenciesLanguage.Instance)

  /** The comment tokens of the language. */
  val Comments: TokenSet = TokenSet.create(SbtDependenciesTokens.COMMENT)

  /** The string-literal tokens of the language: plain strings and dependency strings. */
  val Strings: TokenSet = TokenSet.create(SbtDependenciesTokens.STRING, SbtDependenciesTokens.DEP_STRING)

}

/** PSI file for `dependencies.conf` documents. Also the marker [[SbtDependenciesFormattingService]] uses to decide
  * which files it can format.
  */
final class SbtDependenciesFile(viewProvider: FileViewProvider)
    extends PsiFileBase(viewProvider, SbtDependenciesLanguage.Instance) {

  /** The file type as resolved by the view provider (the `sbt-dependencies` file type). */
  override def getFileType: FileType = getViewProvider.getFileType

  /** Debug name shown in PSI viewers and logs. */
  override def toString: String = "SbtDependenciesFile"

  /** A web reference per dependency entry, opening its source repository (or mvnrepository.com) on Cmd+Click —
    * `findReferenceAt` walks from the leaf under the caret up to the file, so file-level references cover the flat
    * PSI's leaf tokens.
    */
  override def getReferences: Array[PsiReference] =
    SbtDependenciesWebLinks
      .links(getText)
      .map { link =>
        new WebReference(this, new TextRange(link.span.start, link.span.end), SbtDependenciesWebLinks.urlFor(link))
      }
      .toArray

}
