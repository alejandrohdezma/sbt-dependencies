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

package com.alejandrohdezma.sbt.dependencies.intellij.navigation

import java.util

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span
import com.alejandrohdezma.sbt.dependencies.intellij.lang.SbtDependenciesFile
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.Consumer

/** Highlights every occurrence of the entity under the caret in a `dependencies.conf` file: all references of the same
  * `{{variable}}`, or every entry of the same `organization::artifact` — mirroring the VSCode extension's
  * find-references.
  */
final class SbtDependenciesUsagesHandlerFactory extends HighlightUsagesHandlerFactory {

  /** A handler for the caret position, or null when the caret is on neither a variable nor a dependency. */
  override def createHighlightUsagesHandler(editor: Editor, file: PsiFile): HighlightUsagesHandlerBase[?] =
    file match {
      case file: SbtDependenciesFile
          if SbtDependenciesUsagesHandlerFactory
            .usages(file.getText, editor.getCaretModel.getOffset)
            .nonEmpty =>
        new SbtDependenciesUsagesHandlerFactory.Handler(editor, file)
      case _ => null
    }

}

object SbtDependenciesUsagesHandlerFactory {

  private val variablePattern = """\{\{(\w+)\}\}""".r

  private val dependencyPattern =
    """([^\s:"]+)(::?)([^\s:"]+)(?::(\{\{\w+\}\}|\*|[=^~]?\d[^\s:"]*)(?::([^\s:"]+))?)?""".r

  /** The spans of every occurrence of the entity at `offset`: all `{{variable}}` references of the same variable, or
    * the `organization::artifact` portion of every entry with the same coordinate. Empty when the offset is on neither.
    */
  def usages(text: String, offset: Int): List[Span] = {
    val variable = variablePattern
      .findAllMatchIn(text)
      .find(matched => matched.start <= offset && offset < matched.end)
      .map(_.group(1))

    variable match {
      case Some(name) =>
        variablePattern.findAllMatchIn(text).filter(_.group(1) == name).map(m => Span(m.start, m.end)).toList
      case None =>
        dependencyPattern
          .findAllMatchIn(text)
          .find(matched => matched.start <= offset && offset < matched.end)
          .map(matched => (matched.group(1), matched.group(2), matched.group(3)))
          .fold(List.empty[Span]) { key =>
            dependencyPattern
              .findAllMatchIn(text)
              .filter(matched => (matched.group(1), matched.group(2), matched.group(3)) == key)
              .map(matched => Span(matched.start, matched.end(3)))
              .toList
          }
    }
  }

  /** Feeds the computed spans to the platform's usage highlighter. */
  final private class Handler(editor: Editor, file: SbtDependenciesFile)
      extends HighlightUsagesHandlerBase[PsiElement](editor, file) {

    override def getTargets: util.List[PsiElement] = util.List.of(file)

    override def selectTargets(
        targets: util.List[? <: PsiElement],
        consumer: Consumer[? >: util.List[? <: PsiElement]]
    ): Unit =
      consumer.consume(targets)

    override def computeUsages(targets: util.List[? <: PsiElement]): Unit =
      usages(file.getText, editor.getCaretModel.getOffset).foreach { span =>
        myReadUsages.add(new TextRange(span.start, span.end)): Unit
      }

  }

}
