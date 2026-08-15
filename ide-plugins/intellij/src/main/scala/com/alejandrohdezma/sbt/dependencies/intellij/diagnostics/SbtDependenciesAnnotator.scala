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

package com.alejandrohdezma.sbt.dependencies.intellij.diagnostics

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span
import com.alejandrohdezma.sbt.dependencies.document.Diagnostic
import com.alejandrohdezma.sbt.dependencies.document.Diagnostics
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** Surfaces `Diagnostics.check` in the editor: parses the document with the positioned lenient model on a background
  * thread and reports each problem as an error or warning annotation, with the exact messages the SBT plugin itself
  * would fail with.
  */
final class SbtDependenciesAnnotator extends ExternalAnnotator[String, List[Diagnostic]] {

  /** The whole document text, captured on the UI thread. */
  override def collectInformation(file: PsiFile): String = file.getText

  /** Checks the text on a background thread. `DependenciesDocument.parse` is lenient and never throws, so half-typed
    * documents are checked as-is.
    */
  override def doAnnotate(text: String): List[Diagnostic] = Diagnostics.check(DependenciesDocument.parse(text))

  /** Reports each diagnostic at its span, mapping core severities to platform ones. */
  override def apply(file: PsiFile, diagnostics: List[Diagnostic], holder: AnnotationHolder): Unit =
    diagnostics.foreach { diagnostic =>
      val severity = diagnostic.severity match {
        case Diagnostic.Severity.Error   => HighlightSeverity.ERROR
        case Diagnostic.Severity.Warning => HighlightSeverity.WARNING
      }

      SbtDependenciesAnnotator.visibleRange(diagnostic.span, file.getTextLength).foreach { range =>
        holder.newAnnotation(severity, diagnostic.message).range(range).create()
      }
    }

}

object SbtDependenciesAnnotator {

  /** The text range to annotate for a diagnostic span. Zero-width spans (an empty dependency string points at the empty
    * content between its quotes) are widened one character to the right so the annotation stays visible, and spans that
    * outrun the current text (the document changed while checking) are clamped, or dropped when nothing of them
    * remains.
    */
  def visibleRange(span: Span, textLength: Int): Option[TextRange] = {
    val end = math.min(math.max(span.end, span.start + 1), textLength)

    if (span.start < end) Some(new TextRange(span.start, end)) else None
  }

}
