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

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile

/** Intention that renames a `{{variable}}` across the whole file, prompting for the new name — the IntelliJ counterpart
  * of the VSCode extension's variable rename.
  */
final class RenameVariableQuickFix(name: String) extends IntentionAction {

  /** The action name shown in the intentions popup. */
  override def getText: String = s"Rename variable '$name'"

  /** The family the action is grouped under in intention settings. */
  override def getFamilyName: String = "Rename variable"

  /** Always available: the annotation it hangs from is recreated on every change. */
  override def isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean = true

  /** The write action starts after the name prompt. */
  override def startInWriteAction: Boolean = false

  /** No diff preview: the rewrite depends on the name typed in the dialog. */
  override def generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
    IntentionPreviewInfo.EMPTY

  /** Prompts for the new name (braces are stripped if typed) and replaces every `{{name}}` reference in the file. */
  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit =
    Option(Messages.showInputDialog(project, s"Rename variable '$name' to:", "Rename Variable", null, name, null))
      .flatMap(RenameVariableQuickFix.sanitize)
      .filter(_ != name)
      .foreach { newName =>
        WriteAction.run { () =>
          editor.getDocument.setText(RenameVariableQuickFix.rename(editor.getDocument.getText, name, newName))
        }
      }

}

object RenameVariableQuickFix {

  /** The typed name reduced to a valid variable name: surrounding braces and whitespace are stripped, and anything that
    * is not a word returns `None`.
    */
  def sanitize(input: String): Option[String] =
    Some(input.trim.stripPrefix("{{").stripSuffix("}}").trim).filter(_.matches("""\w+"""))

  /** Every `{{from}}` reference replaced by `{{to}}`. */
  def rename(text: String, from: String, to: String): String = text.replace(s"{{$from}}", s"{{$to}}")

}
