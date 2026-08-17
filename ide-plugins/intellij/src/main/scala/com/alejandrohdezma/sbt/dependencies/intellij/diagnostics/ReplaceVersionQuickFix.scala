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

/** Intention that rewrites a dependency's version in place: materializing a `*` into its resolved version or switching
  * a BOM-managed hardcoded version to `*`, as described by the [[SbtDependenciesAnnotator.Rewrite]] it carries.
  */
final class ReplaceVersionQuickFix(rewrite: SbtDependenciesAnnotator.Rewrite) extends IntentionAction {

  /** The action name shown in the intentions popup, e.g. `Replace * with resolved version 2.17.0`. */
  override def getText: String = rewrite.label

  /** The family the action is grouped under in intention settings. */
  override def getFamilyName: String = "Replace dependency version"

  /** Always available: the annotation it hangs from is recreated on every change. */
  override def isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean = true

  /** The rewrite is a document modification. */
  override def startInWriteAction: Boolean = true

  /** Replaces the version span with the rewrite's replacement. Does nothing if the document shrank below the span since
    * the annotation was created.
    */
  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit =
    if (rewrite.span.end <= editor.getDocument.getTextLength)
      editor.getDocument.replaceString(rewrite.span.start, rewrite.span.end, rewrite.replacement)

}
