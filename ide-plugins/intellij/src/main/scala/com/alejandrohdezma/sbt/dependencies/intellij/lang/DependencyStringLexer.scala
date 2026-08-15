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

import scala.collection.mutable.ListBuffer

import com.alejandrohdezma.sbt.dependencies.intellij.lang.SbtDependenciesTokens._
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/** Fragment lexer that splits a quoted dependency string (`"org::name:version:config"`) into fine-grained tokens
  * (organization, separator, artifact, version marker, version, variable, configuration).
  *
  * Registered as a [[com.intellij.lexer.LayeredLexer]] layer on [[SbtDependenciesTokens.DEP_STRING]], so it always
  * lexes a whole fragment at once and doesn't need incremental state. Reuses the SBT plugin's `dependencyRegex` so both
  * sides agree on what a dependency line looks like.
  */
final class DependencyStringLexer extends LexerBase {

  final private case class Token(tokenType: IElementType, start: Int, end: Int)

  private var buf: CharSequence = ""

  private var bufEnd = 0

  private var tokens = List.empty[Token]

  /** Tokenizes the whole `[startOffset, endOffset)` fragment eagerly and positions the lexer on the first token. */
  override def start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int): Unit = {
    buf = buffer
    bufEnd = endOffset
    tokens = tokenize(buffer, startOffset, endOffset)
  }

  /** Always `0`: fragments are lexed whole, so no incremental state needs to be encoded. */
  override def getState: Int = 0

  /** The type of the current token, or `null` once the fragment is exhausted. */
  override def getTokenType: IElementType = tokens.headOption.map(_.tokenType).orNull

  /** Offset where the current token starts, or the fragment end when exhausted. */
  override def getTokenStart: Int = tokens.headOption.map(_.start).getOrElse(bufEnd)

  /** Offset just past the end of the current token, or the fragment end when exhausted. */
  override def getTokenEnd: Int = tokens.headOption.map(_.end).getOrElse(bufEnd)

  /** Moves to the next pre-computed token. */
  override def advance(): Unit = tokens = tokens.drop(1)

  /** The buffer being lexed. */
  override def getBufferSequence: CharSequence = buf

  /** Offset the lexer stops at, as passed to `start`. */
  override def getBufferEnd: Int = bufEnd

  private def tokenize(buffer: CharSequence, start: Int, end: Int): List[Token] = {
    val text = buffer.subSequence(start, end).toString

    val contentStart = if (text.startsWith("\"")) 1 else 0
    val contentEnd   = if (text.length > contentStart && text.endsWith("\"")) text.length - 1 else text.length
    val content      = text.substring(contentStart, contentEnd)

    val result = ListBuffer.empty[Token]

    if (contentStart == 1) result += Token(QUOTE, start, start + 1)

    val matcher = Dependency.dependencyRegex.pattern.matcher(content)

    if (matcher.matches()) {
      val base = start + contentStart

      def add(group: Int, tokenType: IElementType): Unit =
        if (matcher.start(group) >= 0) {
          result += Token(tokenType, base + matcher.start(group), base + matcher.end(group))
        }

      def gap(from: Int, to: Int): Unit =
        if (to > from) result += Token(COLON, base + from, base + to)

      gap(0, matcher.start(1))
      add(1, ORGANIZATION)
      gap(matcher.end(1), matcher.start(2))
      add(2, SEPARATOR)
      gap(matcher.end(2), matcher.start(3))
      add(3, ARTIFACT)

      if (matcher.start(4) >= 0) {
        gap(matcher.end(3), matcher.start(4))
        val version = matcher.group(4)
        val vStart  = base + matcher.start(4)
        if (version == "*") result += Token(BOM_STAR, vStart, vStart + 1)
        else if (version.startsWith("{{")) result += Token(VARIABLE, vStart, vStart + version.length)
        else if (version.nonEmpty && "=^~".contains(version.charAt(0))) {
          result += Token(VERSION_MARKER, vStart, vStart + 1)
          result += Token(VERSION, vStart + 1, vStart + version.length)
        } else result += Token(VERSION, vStart, vStart + version.length)

        if (matcher.start(5) >= 0) {
          gap(matcher.end(4), matcher.start(5))
          add(5, CONFIGURATION)
          gap(matcher.end(5), content.length)
        } else gap(matcher.end(4), content.length)
      } else gap(matcher.end(3), content.length)
    } else if (content.nonEmpty) result += Token(STRING, start + contentStart, start + contentEnd)

    if (contentEnd == text.length - 1) result += Token(QUOTE, start + contentEnd, start + text.length)

    result.toList
  }

}
