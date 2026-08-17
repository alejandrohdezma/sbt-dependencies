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
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Entry
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Intention that adds an empty `note = ""` to the entry at `offset` and places the caret inside the quotes: plain
  * dependency strings are wrapped in object form (`{ dependency = "...", note = "" }`) and object entries get the field
  * inserted right after their dependency string.
  */
final class AddNoteQuickFix(offset: Int) extends IntentionAction {

  /** The action name shown in the intentions popup. */
  override def getText: String = "Add note"

  /** The family the action is grouped under in intention settings. */
  override def getFamilyName: String = getText

  /** Always available: the annotation it hangs from is recreated on every change. */
  override def isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean = true

  /** The rewrite is a document modification. */
  override def startInWriteAction: Boolean = true

  /** Applies the edit computed against the current document text, so the entry is re-located even after the annotation
    * got out of date.
    */
  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit =
    AddNoteQuickFix.edit(editor.getDocument.getText, offset).foreach { edit =>
      editor.getDocument.replaceString(edit.start, edit.end, edit.replacement)
      editor.getCaretModel.moveToOffset(edit.caret)
    }

}

object AddNoteQuickFix {

  /** A text replacement plus where the caret should land afterwards. */
  final case class Edit(start: Int, end: Int, replacement: String, caret: Int)

  /** The edit adding an empty note to the entry containing `offset`, or `None` when the offset is no longer inside an
    * entry that can take one.
    */
  def edit(text: String, offset: Int): Option[Edit] =
    DependenciesDocument
      .parse(text)
      .groups
      .flatMap(_.entries)
      .find(entry => entry.span.start <= offset && offset < entry.span.end)
      .flatMap {
        case line: Entry.DependencyLine =>
          val replacement = s"""{ dependency = "${line.content}", note = "" }"""

          val caret = line.span.start + replacement.indexOf("note = \"") + "note = \"".length

          Some(Edit(line.span.start, line.span.end, replacement, caret))
        case obj: Entry.DependencyObject =>
          obj.dependency.map { dependency =>
            val insertAt = dependency.valueSpan.end + 1

            val replacement = ", note = \"\""

            Edit(insertAt, insertAt, replacement, insertAt + replacement.length - 1)
          }
      }

}
