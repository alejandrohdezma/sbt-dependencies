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
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Intention that applies every BOM-managed rewrite in the document at once, switching each version a visible BOM pins
  * to `*` — the bulk counterpart of [[ReplaceVersionQuickFix]], offered alongside it when more than one line qualifies.
  */
final class ReplaceAllVersionsQuickFix(rewrites: List[SbtDependenciesAnnotator.Rewrite]) extends IntentionAction {

  /** The action name shown in the intentions popup. */
  override def getText: String = s"Replace all ${rewrites.size} BOM-managed versions with *"

  /** The family the action is grouped under in intention settings. */
  override def getFamilyName: String = "Replace dependency version"

  /** Always available: the annotation it hangs from is recreated on every change. */
  override def isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean = true

  /** The rewrite is a document modification. */
  override def startInWriteAction: Boolean = true

  /** Replaces every rewrite's span with `*`, back to front so earlier spans stay valid as the text shrinks. Spans past
    * the current text (the document shrank since the annotation was created) are skipped.
    */
  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit =
    rewrites.sortBy(-_.span.start).foreach { rewrite =>
      if (rewrite.span.end <= editor.getDocument.getTextLength)
        editor.getDocument.replaceString(rewrite.span.start, rewrite.span.end, rewrite.replacement)
    }

}
