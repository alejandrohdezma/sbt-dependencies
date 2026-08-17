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

import java.nio.file.Path

import scala.util.Try

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Entry
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span
import com.alejandrohdezma.sbt.dependencies.document.Diagnostic
import com.alejandrohdezma.sbt.dependencies.document.Diagnostics
import com.alejandrohdezma.sbt.dependencies.intellij.resolutions.Resolutions
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** Surfaces `Diagnostics.check` in the editor: parses the document with the positioned lenient model on a background
  * thread and reports each problem as an error or warning annotation, with the exact messages the SBT plugin itself
  * would fail with. When a fresh `.sbt-resolutions` dump is available it also offers version rewrites as intentions:
  * materializing a `*` into its resolved version and switching a hardcoded version a BOM manages to `*`.
  */
final class SbtDependenciesAnnotator
    extends ExternalAnnotator[SbtDependenciesAnnotator.Info, SbtDependenciesAnnotator.Result] {

  /** The whole document text and the file's path (for locating the `.sbt-resolutions` dumps), captured on the UI
    * thread.
    */
  override def collectInformation(file: PsiFile): SbtDependenciesAnnotator.Info =
    SbtDependenciesAnnotator.Info(
      file.getText,
      Option(file.getVirtualFile).flatMap(file => Try(file.toNioPath).toOption)
    )

  /** Checks the text on a background thread, pairing each removable diagnostic with its enclosing entry so a quick fix
    * can delete it, and computing the available version rewrites. `DependenciesDocument.parse` is lenient and never
    * throws, so half-typed documents are checked as-is.
    */
  override def doAnnotate(info: SbtDependenciesAnnotator.Info): SbtDependenciesAnnotator.Result = {
    val document = DependenciesDocument.parse(info.text)

    val found = Diagnostics.check(document).map { diagnostic =>
      val entrySpan =
        if (SbtDependenciesAnnotator.removable(diagnostic.message))
          SbtDependenciesAnnotator.enclosingEntry(document, diagnostic.span)
        else None

      SbtDependenciesAnnotator.Found(diagnostic, entrySpan)
    }

    val lookup = info.path.flatMap(path => Resolutions.lookupFor(path, info.text))

    SbtDependenciesAnnotator.Result(
      found,
      SbtDependenciesAnnotator.rewrites(document, lookup),
      SbtDependenciesAnnotator.missingNotes(document)
    )
  }

  /** Reports each diagnostic at its span, mapping core severities to platform ones and attaching a
    * [[RemoveEntryQuickFix]] to the removable ones. Version rewrites become invisible annotations that only surface
    * their intention on Alt+Enter.
    */
  override def apply(file: PsiFile, result: SbtDependenciesAnnotator.Result, holder: AnnotationHolder): Unit = {
    result.found.foreach { case SbtDependenciesAnnotator.Found(diagnostic, entrySpan) =>
      val severity = diagnostic.severity match {
        case Diagnostic.Severity.Error   => HighlightSeverity.ERROR
        case Diagnostic.Severity.Warning => HighlightSeverity.WARNING
      }

      SbtDependenciesAnnotator.visibleRange(diagnostic.span, file.getTextLength).foreach { range =>
        val annotation = holder.newAnnotation(severity, diagnostic.message).range(range)

        entrySpan.fold(annotation)(span => annotation.withFix(new RemoveEntryQuickFix(span))).create()
      }
    }

    result.rewrites.foreach { rewrite =>
      SbtDependenciesAnnotator.visibleRange(rewrite.span, file.getTextLength).foreach { range =>
        rewrite.message
          .fold(holder.newSilentAnnotation(HighlightSeverity.INFORMATION))(message =>
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING, message)
          )
          .range(range)
          .withFix(new ReplaceVersionQuickFix(rewrite))
          .create()
      }
    }

    result.missingNotes.foreach { missing =>
      SbtDependenciesAnnotator.visibleRange(missing.entrySpan, file.getTextLength).foreach { range =>
        holder
          .newAnnotation(HighlightSeverity.WEAK_WARNING, missing.message)
          .range(range)
          .withFix(new AddNoteQuickFix(missing.entrySpan.start))
          .create()
      }
    }
  }

}

object SbtDependenciesAnnotator {

  /** What the annotator collects on the UI thread: the document text and the file's path on disk. */
  final case class Info(text: String, path: Option[Path])

  /** What the background pass produces: positioned diagnostics, available version rewrites and entries that should
    * document themselves with a note.
    */
  final case class Result(found: List[Found], rewrites: List[Rewrite], missingNotes: List[MissingNote])

  /** An entry that pins or restricts a dependency without a note explaining why. */
  final case class MissingNote(entrySpan: Span, message: String)

  /** A version rewrite offered as an intention: replacing `span` with `replacement`. Rewrites with a `message` are
    * additionally reported as weak warnings so the option is visible without pressing Alt+Enter.
    */
  final case class Rewrite(span: Span, replacement: String, label: String, message: Option[String] = None)

  /** A diagnostic paired with the span of its enclosing entry, present only when the diagnostic can be fixed by
    * removing the entry.
    */
  final case class Found(diagnostic: Diagnostic, entrySpan: Option[Span])

  /** The version rewrites the dump enables on plain dependency strings: `*` materializes into its resolved version
    * (on-demand intention), and a hardcoded version a visible BOM manages switches to `*` (reported as a weak warning
    * so the option is visible). Spans always come from the current text, so the offers survive unrelated edits.
    */
  def rewrites(document: DependenciesDocument, lookup: Option[Resolutions.Lookup]): List[Rewrite] =
    lookup.fold(List.empty[Rewrite]) { lookup =>
      document.groups.flatMap { group =>
        group.entries.collect { case line: Entry.DependencyLine => line }.flatMap { line =>
          Dependency.dependencyRegex.findFirstMatchIn(line.content).filter(_.group(4) != null).flatMap { matched =>
            val org     = matched.group(1)
            val isCross = matched.group(2) == "::"
            val name    = matched.group(3)
            val version = matched.group(4)
            val span    = Span(line.contentSpan.start + matched.start(4), line.contentSpan.start + matched.end(4))

            version match {
              case "*" =>
                lookup
                  .resolveWildcard(group.name, org, name, isCross)
                  .map(pin => Rewrite(span, pin.version, s"Replace * with resolved version ${pin.version}"))
              case version if version.startsWith("{{")                                     => None
              case _ if Set("bom", "sbt-plugin").contains(Option(matched.group(5)).orNull) => None
              case version                                                                 =>
                lookup
                  .resolveWildcard(group.name, org, name, isCross)
                  .map(pin =>
                    Rewrite(
                      span,
                      "*",
                      s"Replace $version with * (managed by ${pin.bomName})",
                      Some(
                        s"$org:$name is managed by ${pin.bomOrganization}:${pin.bomName}:${pin.bomVersion} — " +
                          "the version can be replaced with *"
                      )
                    )
                  )
            }
          }
        }
      }
    }

  /** The entries that should carry a note: plain dependency strings with a pinned version marker (`=`, `^`, `~`) and
    * object entries marked `intransitive` without one — mirroring the VSCode extension's CodeLens hints.
    */
  def missingNotes(document: DependenciesDocument): List[MissingNote] =
    document.groups.flatMap(_.entries).flatMap {
      case line: Entry.DependencyLine
          if Dependency.dependencyRegex
            .findFirstMatchIn(line.content)
            .exists(matched => Option(matched.group(4)).exists(_.matches("^[=^~].*"))) =>
        Some(
          MissingNote(
            line.span,
            "Pinned without note — consider adding { dependency = \"...\", note = \"...\" }"
          )
        )
      case obj: Entry.DependencyObject if obj.intransitive && obj.note.isEmpty && obj.dependency.isDefined =>
        Some(MissingNote(obj.span, "Intransitive without note — consider adding note = \"...\""))
      case _ => None
    }

  /** Whether removing the offending entry fixes the diagnostic. */
  def removable(message: String): Boolean =
    message == "Empty dependency string" || message.startsWith("duplicate dependency entry:")

  /** The span of the entry containing `span`, if any. */
  def enclosingEntry(document: DependenciesDocument, span: Span): Option[Span] =
    document.groups
      .flatMap(_.entries)
      .find(entry => entry.span.start <= span.start && span.end <= entry.span.end)
      .map(_.span)

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
