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

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener

/** Keeps object-entry foldings in sync with the caret, mirroring the VSCode extension's note decorations: the entry
  * under the caret expands so it can be edited, and collapses back as soon as the caret leaves it.
  */
final class SbtDependenciesFoldingCaretListener extends EditorFactoryListener {

  /** Attaches the caret listener to every editor opened on a `dependencies.conf` file. */
  override def editorCreated(event: EditorFactoryEvent): Unit = {
    val editor = event.getEditor

    if (Option(editor.getVirtualFile).exists(_.getName == "dependencies.conf"))
      editor.getCaretModel.addCaretListener(new CaretListener {
        override def caretPositionChanged(event: CaretEvent): Unit =
          SbtDependenciesFoldingCaretListener.update(editor)
      })
  }

}

object SbtDependenciesFoldingCaretListener {

  /** Expands both foldings of the object entry containing the caret and collapses every other one. Only regions
    * produced by [[SbtDependenciesFoldingBuilder]] are touched, identified by their spans in the current text.
    */
  def update(editor: Editor): Unit = {
    val offset = editor.getCaretModel.getOffset

    val foldings = SbtDependenciesFoldingBuilder.foldings(editor.getDocument.getText)

    val regions = editor.getFoldingModel.getAllFoldRegions.flatMap { region =>
      foldings
        .find(folding => folding.region.start == region.getStartOffset && folding.region.end == region.getEndOffset)
        .map(region -> _.entry)
    }

    def expected(entry: Span): Boolean =
      entry.start <= offset && offset <= entry.end

    val stale = regions.exists { case (region, entry) => region.isExpanded != expected(entry) }

    if (stale)
      editor.getFoldingModel.runBatchFoldingOperation { () =>
        regions.foreach { case (region, entry) =>
          if (region.isExpanded != expected(entry)) region.setExpanded(expected(entry))
        }
      }
  }

}
