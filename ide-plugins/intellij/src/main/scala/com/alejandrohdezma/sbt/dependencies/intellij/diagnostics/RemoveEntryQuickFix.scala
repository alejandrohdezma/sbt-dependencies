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

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Quick fix that deletes a dependency entry, offered on removable diagnostics (duplicate or empty entries). `span`
  * covers the entry as reported by the annotator; the actual deletion range is recomputed against the current document
  * text when the fix is invoked.
  */
final class RemoveEntryQuickFix(span: Span) extends IntentionAction {

  /** The action name shown in the quick-fix popup. */
  override def getText: String = "Remove dependency entry"

  /** The family the action is grouped under in intention settings. */
  override def getFamilyName: String = getText

  /** Always available: the annotation it hangs from is recreated on every change. */
  override def isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean = true

  /** The deletion is a document modification. */
  override def startInWriteAction: Boolean = true

  /** Deletes the entry, expanded to whole lines when nothing else shares them. Does nothing if the document shrank
    * below the entry since the annotation was created.
    */
  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit = {
    val text = editor.getDocument.getText

    if (span.end <= text.length) {
      val deletion = RemoveEntryQuickFix.deletionRange(text, span)

      editor.getDocument.deleteString(deletion.start, deletion.end)
    }
  }

}

object RemoveEntryQuickFix {

  /** The range to delete for the entry at `span`: the entry expanded to its full lines (trailing newline included) when
    * only whitespace surrounds it on those lines, or the entry text alone when it shares a line with anything else
    * (single-line groups, trailing comments).
    */
  def deletionRange(text: String, span: Span): Span = {
    val lineStart = text.lastIndexOf('\n', span.start - 1) + 1
    val lineEnd   = text.indexOf('\n', span.end) match {
      case -1    => text.length
      case index => index
    }

    val before = text.substring(lineStart, span.start)
    val after  = text.substring(span.end, lineEnd)

    if (before.trim.isEmpty && after.trim.isEmpty) Span(lineStart, math.min(lineEnd + 1, text.length))
    else span
  }

}
