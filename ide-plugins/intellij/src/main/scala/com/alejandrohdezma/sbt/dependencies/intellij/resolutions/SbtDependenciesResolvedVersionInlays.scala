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

package com.alejandrohdezma.sbt.dependencies.intellij.resolutions

import java.awt.Graphics
import java.awt.Rectangle
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters._
import scala.util.Try

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Entry
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer

/** Shows the concrete version of `*` and `{{variable}}` dependencies as ghost text after the string (` = 2.17.0`, with
  * a ` (stale)` suffix when the file changed since the last sbt reload), read from the `.sbt-resolutions` dump the sbt
  * plugin writes on load — mirroring the VSCode extension's resolved-version decorations.
  */
final class SbtDependenciesResolvedVersionInlays extends EditorFactoryListener {

  private val listeners = new ConcurrentHashMap[Editor, Disposable]()

  /** Renders the inlays when an editor opens on a `dependencies.conf` file and refreshes them on every change. */
  override def editorCreated(event: EditorFactoryEvent): Unit = {
    val editor = event.getEditor

    if (Option(editor.getVirtualFile).exists(_.getName == "dependencies.conf")) {
      SbtDependenciesResolvedVersionInlays.refresh(editor)

      val listener = new DocumentListener {
        override def documentChanged(event: DocumentEvent): Unit =
          ApplicationManager.getApplication.invokeLater(() => SbtDependenciesResolvedVersionInlays.refresh(editor))
      }

      val disposable = Disposer.newDisposable("sbt-dependencies-resolved-version-inlays")

      editor.getDocument.addDocumentListener(listener, disposable)
      listeners.put(editor, disposable): Unit
    }
  }

  /** Detaches the document listener when its editor closes. */
  override def editorReleased(event: EditorFactoryEvent): Unit =
    Option(listeners.remove(event.getEditor)).foreach(Disposer.dispose)

}

object SbtDependenciesResolvedVersionInlays {

  /** Recomputes and replaces this file's resolved-version inlays against the current text and dumps. */
  def refresh(editor: Editor): Unit =
    if (!editor.isDisposed) {
      val decorations = Option(editor.getVirtualFile)
        .flatMap(file => Try(file.toNioPath).toOption)
        .flatMap(path => Resolutions.lookupFor(path, editor.getDocument.getText))
        .map(lookup => decorate(editor.getDocument.getText, lookup))
        .getOrElse(Nil)

      val model = editor.getInlayModel

      model
        .getInlineElementsInRange(0, editor.getDocument.getTextLength)
        .asScala
        .filter(_.getRenderer.isInstanceOf[Renderer])
        .foreach(Disposer.dispose)

      decorations.foreach { case (offset, text) =>
        model.addInlineElement(offset, true, new Renderer(text))
      }
    }

  /** The ghost texts for a document: one per plain dependency string whose `*` or `{{variable}}` version the dump
    * resolves, positioned right after the closing quote.
    */
  def decorate(text: String, lookup: Resolutions.Lookup): List[(Int, String)] =
    DependenciesDocument.parse(text).groups.flatMap { group =>
      group.entries.collect { case line: Entry.DependencyLine => line }.flatMap { line =>
        line.content match {
          case Dependency.dependencyRegex(org, separator, name, version, _) =>
            val isCross = separator == "::"

            val resolved = Option(version) match {
              case Some("*")                                 => lookup.resolveWildcard(group.name, org, name, isCross).map(_.version)
              case Some(version) if version.startsWith("{{") =>
                lookup.resolveVariable(group.name, org, name, isCross).map(_.version)
              case _ => None
            }

            resolved.map { version =>
              (line.span.end, s" = $version${if (lookup.stale) " (stale)" else ""}")
            }
          case _ => None
        }
      }
    }

  /** Paints the ghost text with the line-comment color of the current scheme, in italics. */
  final private class Renderer(val text: String) extends EditorCustomElementRenderer {

    override def calcWidthInPixels(inlay: Inlay[?]): Int =
      inlay.getEditor.getContentComponent.getFontMetrics(font(inlay.getEditor)).stringWidth(text)

    override def paint(inlay: Inlay[?], g: Graphics, target: Rectangle, attributes: TextAttributes): Unit = {
      val editor = inlay.getEditor

      val color = Option(
        editor.getColorsScheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT).getForegroundColor
      ).getOrElse(editor.getColorsScheme.getDefaultForeground)

      g.setColor(color)
      g.setFont(font(editor))
      g.drawString(text, target.x, target.y + editor.getAscent)
    }

    private def font(editor: Editor) = editor.getColorsScheme.getFont(EditorFontType.ITALIC)

  }

}
