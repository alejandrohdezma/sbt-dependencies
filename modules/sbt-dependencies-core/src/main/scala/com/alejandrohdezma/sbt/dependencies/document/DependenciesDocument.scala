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

package com.alejandrohdezma.sbt.dependencies.document

import scala.collection.mutable.ListBuffer

import com.alejandrohdezma.sbt.dependencies.model.Fields

/** A positioned, lenient view of a `dependencies.conf` document, for editor features (structure views, diagnostics,
  * hovers). Best-effort by construction: it never fails and returns whatever structure the text currently has, even
  * mid-edit — unlike the strict round-trip model (`GroupConfig`), which refuses documents it doesn't fully understand
  * and drops all source positions.
  */
final case class DependenciesDocument(groups: List[DependenciesDocument.Group])

object DependenciesDocument {

  /** Absolute character offsets `[start, end)` into the parsed text. */
  final case class Span(start: Int, end: Int)

  /** A value with the span it occupies in the text. */
  final case class Field(value: String, valueSpan: Span)

  /** A group and everything it declares. `span` runs from the header to the closing bracket/brace, or to the end of the
    * text when the group is still unclosed.
    */
  final case class Group(
      name: String,
      nameSpan: Span,
      kind: Group.Kind,
      settings: List[Setting],
      entries: List[Entry],
      span: Span
  )

  object Group {

    /** Whether the group uses the simple (`name = [...]`) or advanced (`name { ... }`) format. */
    sealed trait Kind

    object Kind {

      case object Simple extends Kind

      case object Advanced extends Kind

    }

  }

  /** A setting line inside an advanced group block (`scala-version`, `java-version`...). */
  final case class Setting(key: String, keySpan: Span, span: Span)

  /** A dependency entry inside a group. */
  sealed trait Entry {

    /** The whole entry, quotes/braces included. */
    def span: Span

    /** The dependency line this entry declares: the quoted content for plain entries, the `dependency` field for object
      * entries (absent while the field hasn't been typed yet).
      */
    def dependency: Option[Field]

  }

  object Entry {

    /** A plain quoted dependency line. `contentSpan` covers the text inside the quotes. */
    final case class DependencyLine(content: String, contentSpan: Span, span: Span) extends Entry {

      /** The quoted content as the dependency line. */
      override def dependency: Option[Field] = Some(Field(content, contentSpan))

    }

    /** An object entry (`{ dependency = ..., note = ... }`), single- or multi-line. */
    final case class DependencyObject(
        dependency: Option[Field],
        note: Option[Field],
        intransitive: Boolean,
        scalaFilter: Option[Field],
        crossVersion: Option[Field],
        span: Span
    ) extends Entry

  }

  private val simpleGroupStart = """^(\s*)([\w][\w.-]*)\s*=\s*\[""".r

  private val advancedGroupStart = """^(\s*)([\w][\w.-]*)\s*\{""".r

  private val dependenciesArrayStart = ("""^\s*""" + Fields.Dependencies + """\s*=\s*\[""").r

  private val settingLine = """^(\s*)([\w][\w.-]*)\s*=""".r

  private val dependencyField = (Fields.Dependency + """\s*=\s*"([^"]*)"""").r

  private val noteField = (Fields.Note + """\s*=\s*"([^"]*)"""").r

  private val intransitiveField = (Fields.Intransitive + """\s*=\s*true""").r

  private val scalaFilterField = (Fields.ScalaFilter + """\s*=\s*"([^"]*)"""").r

  private val crossVersionField = (Fields.CrossVersion + """\s*=\s*"([^"]*)"""").r

  private val singleLineObject = """\{(?:[^}"{]*(?:"[^"]*")?)*\}""".r

  private val quotedString = """"([^"]*)"""".r

  /** Parses `text` into its positioned document view. Never throws. */
  def parse(text: String): DependenciesDocument = new Parser(text).parse()

  /** Line-based state machine mirroring the VSCode extension's `parser.ts`, with two deliberate deviations: comments
    * are blanked out (replaced by spaces) instead of excised so columns stay aligned with the raw text, and unclosed
    * groups/objects are flushed at end of input instead of dropped so half-typed documents still produce structure.
    */
  final private class Parser(text: String) {

    private val lines = text.split("\n", -1)

    private val lineStarts = lines.scanLeft(0)((offset, line) => offset + line.length + 1)

    private val groups = ListBuffer.empty[Group]

    private var state: State = State.Outside

    private var inBlockComment = false

    // Current group under construction
    private var groupName = ""

    private var groupNameSpan = Span(0, 0)

    private var groupKind: Group.Kind = Group.Kind.Simple

    private var groupStart = 0

    private val groupSettings = ListBuffer.empty[Setting]

    private val groupEntries = ListBuffer.empty[Entry]

    // Multi-line object under construction
    private var objectStart = 0

    private var objectDependency = Option.empty[Field]

    private var objectNote = Option.empty[Field]

    private var objectIntransitive = false

    private var objectScalaFilter = Option.empty[Field]

    private var objectCrossVersion = Option.empty[Field]

    private var preObjectState: State = State.SimpleArray

    def parse(): DependenciesDocument = {
      lines.indices.foreach(handleLine)

      state match {
        case State.DependencyObject => flushObject(text.length)
        case _                      => ()
      }

      state match {
        case State.Outside => ()
        case _             => flushGroup(text.length)
      }

      DependenciesDocument(groups.toList)
    }

    private def handleLine(index: Int): Unit = {
      val raw       = lines(index)
      val effective = blankComments(raw)
      val offset    = lineStarts(index)

      state match {
        case State.DependencyObject =>
          trackObjectFields(effective, offset)
          if (stripQuotedStrings(effective).contains("}")) {
            flushObject(offset + effective.lastIndexOf("}") + 1)
            state = preObjectState
          }

        case State.Outside =>
          simpleGroupStart.findFirstMatchIn(effective) match {
            case Some(m) =>
              startGroup(m.group(2), offset + m.start(2), Group.Kind.Simple, offset + m.start(2))
              if (effective.contains("]")) {
                emitDependenciesOnLine(effective, offset)
                flushGroup(offset + effective.lastIndexOf("]") + 1)
              } else state = State.SimpleArray
            case None =>
              advancedGroupStart.findFirstMatchIn(effective).foreach { m =>
                startGroup(m.group(2), offset + m.start(2), Group.Kind.Advanced, offset + m.start(2))
                state = State.AdvancedBlock
              }
          }

        case State.AdvancedBlock =>
          if (dependenciesArrayStart.findFirstIn(effective).isDefined) {
            if (effective.contains("]")) emitDependenciesOnLine(effective, offset)
            else state = State.DependenciesArray
          } else if (effective.contains("}")) {
            flushGroup(offset + effective.lastIndexOf("}") + 1)
            state = State.Outside
          } else {
            settingLine.findFirstMatchIn(effective).foreach { m =>
              val keySpan = Span(offset + m.start(2), offset + m.end(2))
              groupSettings += Setting(m.group(2), keySpan, Span(offset + m.start(2), offset + effective.length))
            }
          }

        case State.SimpleArray | State.DependenciesArray =>
          val unquoted = stripQuotedStrings(effective)

          if (unquoted.contains("{") && !unquoted.contains("}")) {
            preObjectState = state
            startObject(offset + effective.indexOf("{"))
            trackObjectFields(effective, offset)
            state = State.DependencyObject
          } else {
            emitDependenciesOnLine(effective, offset)

            if (effective.contains("]")) {
              state match {
                case State.SimpleArray =>
                  flushGroup(offset + effective.lastIndexOf("]") + 1)
                  state = State.Outside
                case _ =>
                  state = State.AdvancedBlock
              }
            }
          }
      }
    }

    /** Replaces comments with spaces, preserving every other character's column. Handles line comments (`//`, `#`,
      * quote-aware so they never trigger inside strings) and block comments spanning lines.
      */
    private def blankComments(line: String): String = {
      val result = new StringBuilder(line)

      var pos = 0
      while (pos < result.length) {
        if (inBlockComment) {
          val end = result.indexOf("*/", pos)
          val to  = if (end == -1) result.length else end + 2
          blank(result, pos, to)
          if (end != -1) inBlockComment = false
          pos = to
        } else {
          val start = result.indexOf("/*", pos)
          if (start == -1) pos = result.length
          else {
            inBlockComment = true
            pos = start
          }
        }
      }

      if (!inBlockComment) {
        val stripped   = stripQuotedStrings(result.toString)
        val lineIdx    = stripped.indexOf("//")
        val hashIdx    = stripped.indexOf("#")
        val commentIdx = List(lineIdx, hashIdx).filter(_ != -1).sorted.headOption

        commentIdx.foreach(idx => blank(result, idx, result.length))
      }

      result.toString
    }

    private def blank(builder: StringBuilder, from: Int, to: Int): Unit = {
      var i = from
      while (i < to) {
        builder.setCharAt(i, ' ')
        i += 1
      }
    }

    private def stripQuotedStrings(line: String): String = quotedString.replaceAllIn(line, m => " " * m.matched.length)

    private def startGroup(name: String, nameStart: Int, kind: Group.Kind, start: Int): Unit = {
      groupName = name
      groupNameSpan = Span(nameStart, nameStart + name.length)
      groupKind = kind
      groupStart = start
      groupSettings.clear()
      groupEntries.clear()
    }

    private def flushGroup(end: Int): Unit = {
      groups += Group(
        groupName,
        groupNameSpan,
        groupKind,
        groupSettings.toList,
        groupEntries.toList,
        Span(groupStart, end)
      )
      state = State.Outside
    }

    private def startObject(start: Int): Unit = {
      objectStart = start
      objectDependency = None
      objectNote = None
      objectIntransitive = false
      objectScalaFilter = None
      objectCrossVersion = None
    }

    private def trackObjectFields(line: String, offset: Int): Unit = {
      def field(regex: scala.util.matching.Regex): Option[Field] =
        regex.findFirstMatchIn(line).map(m => Field(m.group(1), Span(offset + m.start(1), offset + m.end(1))))

      objectDependency = objectDependency.orElse(field(dependencyField))
      objectNote = objectNote.orElse(field(noteField))
      objectIntransitive = objectIntransitive || intransitiveField.findFirstIn(line).isDefined
      objectScalaFilter = objectScalaFilter.orElse(field(scalaFilterField))
      objectCrossVersion = objectCrossVersion.orElse(field(crossVersionField))
    }

    private def flushObject(end: Int): Unit =
      groupEntries += Entry.DependencyObject(
        objectDependency,
        objectNote,
        objectIntransitive,
        objectScalaFilter,
        objectCrossVersion,
        Span(objectStart, end)
      )

    /** Emits single-line object entries and plain string entries found on a line. Objects are detected first (skipping
      * `{...}` matches inside quoted strings via quote parity, so `{{var}}` never opens an object); remaining quoted
      * strings outside object ranges become plain dependency lines.
      */
    private def emitDependenciesOnLine(line: String, offset: Int): Unit = {
      val objectRanges = singleLineObject
        .findAllMatchIn(line)
        .filter(m => line.substring(0, m.start).count(_ == '"') % 2 == 0)
        .map { m =>
          startObject(offset + m.start)
          trackObjectFields(m.matched, offset + m.start)
          flushObject(offset + m.end)
          (m.start, m.end)
        }
        .toList

      quotedString.findAllMatchIn(line).foreach { m =>
        val inObject = objectRanges.exists { case (start, end) => m.start >= start && m.start < end }

        if (!inObject) {
          val contentSpan = Span(offset + m.start + 1, offset + m.end - 1)
          groupEntries += Entry.DependencyLine(m.group(1), contentSpan, Span(offset + m.start, offset + m.end))
        }
      }
    }

  }

  sealed private trait State

  private object State {

    case object Outside extends State

    case object SimpleArray extends State

    case object AdvancedBlock extends State

    case object DependenciesArray extends State

    case object DependencyObject extends State

  }

}
