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

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Entry
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange

/** Folds the object syntax of single-line object entries away, leaving the dependency string as regular highlighted
  * text: `{ dependency = "org::artifact:1.0.0", note = "why" }` shows as `"org::artifact:1.0.0" // why` — the IntelliJ
  * counterpart of the VSCode extension's note decorations. Each entry gets two regions: the `{ dependency = ` prefix
  * (collapsing to a space) and the `, note = "..." }` suffix (collapsing to a comment-style annotation), so the quoted
  * dependency in between keeps its syntax colors.
  */
final class SbtDependenciesFoldingBuilder extends FoldingBuilder {

  /** Two folding regions per single-line object entry with a note or scala-filter annotation. */
  override def buildFoldRegions(node: ASTNode, document: Document): Array[FoldingDescriptor] =
    SbtDependenciesFoldingBuilder
      .foldings(document.getText)
      .map(folding =>
        new FoldingDescriptor(node, new TextRange(folding.region.start, folding.region.end), null, folding.placeholder)
      )
      .toArray

  /** Unused: every descriptor carries its own placeholder. */
  override def getPlaceholderText(node: ASTNode): String = "..."

  /** Entries fold as soon as the file opens. */
  override def isCollapsedByDefault(node: ASTNode): Boolean = true

}

object SbtDependenciesFoldingBuilder {

  /** A foldable region and its placeholder, tagged with the span of the entry it belongs to so both regions of an entry
    * can be expanded and collapsed as one.
    */
  final case class Folding(entry: Span, region: Span, placeholder: String)

  /** The foldable regions of a document: for every single-line object entry that declares a dependency and a note (or a
    * scala-filter), the `{ dependency = ` prefix before the opening quote and the `, note = "..." }` suffix after the
    * closing quote.
    */
  def foldings(text: String): List[Folding] =
    DependenciesDocument
      .parse(text)
      .groups
      .flatMap(_.entries)
      .collect {
        case entry: Entry.DependencyObject if singleLine(text, entry.span) =>
          entry.dependency.flatMap { dependency =>
            val annotation = entry.note
              .map(_.value)
              .orElse(entry.scalaFilter.map(filter => s"only for Scala ${filter.value}"))

            annotation.map { annotation =>
              List(
                Folding(entry.span, Span(entry.span.start, dependency.valueSpan.start - 1), ""),
                Folding(entry.span, Span(dependency.valueSpan.end + 1, entry.span.end), s" // $annotation")
              )
            }
          }
      }
      .flatten
      .flatten

  private def singleLine(text: String, span: Span): Boolean =
    !text.substring(span.start, span.end).contains("\n")

}
