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

package com.alejandrohdezma.sbt.dependencies.io

import scala.jdk.CollectionConverters._

import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.typesafe.config.Config
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValueType

/** A dependency entry that may optionally carry a note and/or intransitive flag. */
final case class AnnotatedDependency(line: String, note: Option[String] = None, intransitive: Boolean = false) {

  /** Formats a single dependency entry as HOCON, using single-line object format if it fits within the max line length
    * (120 characters), or multi-line otherwise.
    */
  def format: String =
    if (note.isEmpty && !intransitive) s""""$line""""
    else if (singleLine.length <= 120) singleLine
    else multiLine

  /** Formats a single dependency entry as a multi-line object. */
  lazy val multiLine = {
    val noteField         = note.map(n => s"""  note = "$n"\n""").getOrElse("")
    val intransitiveField = if (intransitive) "  intransitive = true\n" else ""

    s"""{
       |  dependency = "$line"
       |$noteField$intransitiveField}""".stripMargin
  }

  /** Formats a single dependency entry as a single-line object. */
  lazy val singleLine = s"""{ dependency = "$line", $extraFields }"""

  private def extraFields: String = {
    val noteField         = note.map(n => s"""note = "$n"""")
    val intransitiveField = if (intransitive) Some("intransitive = true") else None

    List(noteField, intransitiveField).flatten.mkString(", ")
  }

}

object AnnotatedDependency {

  final case class NoteKey(organization: String, name: String, configuration: String)

  /** Holds the annotation data (note + intransitive flag) for a dependency, used during write preservation. */
  final case class AnnotationData(note: Option[String], intransitive: Boolean)

  /** Parses a dependency list that may contain both plain strings and annotated objects. */
  def parse(config: Config, path: String): Either[String, List[AnnotatedDependency]] =
    config
      .getList(path)
      .asScala
      .toList
      .foldLeft(Right(List.empty[AnnotatedDependency]): Either[String, List[AnnotatedDependency]]) {
        // If the accumulator already contains an error, return it immediately
        case (Left(err), _) => Left(err)

        // Otherwise, try to parse the value as a string or object
        case (Right(acc), value) =>
          value.valueType() match {
            // If the value is a string, create a new annotated dependency with no note
            case ConfigValueType.STRING =>
              Right(acc :+ AnnotatedDependency(value.unwrapped().asInstanceOf[String]))

            // If the value is an object, check for 'dependency' and at least 'note' or 'intransitive'
            case ConfigValueType.OBJECT =>
              val obj = value.asInstanceOf[ConfigObject].toConfig
              if (!obj.hasPath("dependency")) Left("object entry must have a 'dependency' field")
              else {
                val note           = if (obj.hasPath("note")) Some(obj.getString("note")) else None
                val isIntransitive = obj.hasPath("intransitive") && obj.getBoolean("intransitive")

                if (note.isEmpty && !isIntransitive) Left("object entry must have a 'note' or 'intransitive' field")
                else Right(acc :+ AnnotatedDependency(obj.getString("dependency"), note, isIntransitive))
              }

            // If the value is anything else, return an error
            case other =>
              Left(s"expected string or object in dependency list, got $other")
          }
      }

  /** Creates an annotated dependency from a dependency and a map of existing annotations. */
  def from(annotations: Map[NoteKey, AnnotationData])(dep: Dependency): AnnotatedDependency = {
    val key  = NoteKey(dep.organization, dep.name, dep.configuration)
    val data = annotations.get(key)

    AnnotatedDependency(dep.toLine, data.flatMap(_.note), data.exists(_.intransitive))
  }

}
