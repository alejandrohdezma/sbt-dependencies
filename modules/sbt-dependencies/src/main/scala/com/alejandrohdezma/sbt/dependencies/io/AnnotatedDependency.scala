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

import sbt.librarymanagement.CrossVersion
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import sbt.librarymanagement.ModuleID
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.finders.ArtifactKind
import com.alejandrohdezma.sbt.dependencies.finders.Utils
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.typesafe.config.Config
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValueType

/** A dependency entry that may optionally carry a note, intransitive flag, scala-filter, and/or cross-version override.
  *
  * The `crossVersion` value, when set, overrides the `CrossVersion` that would otherwise be derived from the
  * dependency's `isCross` flag and configuration. Legal values: `"full"`, `"binary"`, `"patch"`, `"disabled"`.
  */
final case class AnnotatedDependency(
    line: String,
    note: Option[String] = None,
    intransitive: Boolean = false,
    scalaFilter: Option[String] = None,
    crossVersion: Option[String] = None
) {

  /** Formats a single dependency entry as HOCON, using single-line object format if it fits within the max line length
    * (120 characters), or multi-line otherwise.
    */
  def format: String =
    if (note.isEmpty && !intransitive && scalaFilter.isEmpty && crossVersion.isEmpty) s""""$line""""
    else if (singleLine.length <= 120) singleLine
    else multiLine

  /** Formats a single dependency entry as a multi-line object. */
  lazy val multiLine = {
    val noteField         = note.map(n => s"""  note = "$n"\n""").getOrElse("")
    val intransitiveField = if (intransitive) "  intransitive = true\n" else ""
    val scalaFilterField  = scalaFilter.map(f => s"""  scala-filter = "$f"\n""").getOrElse("")
    val crossVersionField = crossVersion.map(c => s"""  cross-version = "$c"\n""").getOrElse("")

    s"""{
       |  dependency = "$line"
       |$noteField$intransitiveField$scalaFilterField$crossVersionField}""".stripMargin
  }

  /** Formats a single dependency entry as a single-line object. */
  lazy val singleLine = s"""{ dependency = "$line", $extraFields }"""

  private def extraFields: String = {
    val noteField         = note.map(n => s"""note = "$n"""")
    val intransitiveField = if (intransitive) Some("intransitive = true") else None
    val scalaFilterField  = scalaFilter.map(f => s"""scala-filter = "$f"""")
    val crossVersionField = crossVersion.map(c => s"""cross-version = "$c"""")

    List(noteField, intransitiveField, scalaFilterField, crossVersionField).flatten.mkString(", ")
  }

}

object AnnotatedDependency {

  final case class Resolved(
      dependency: Dependency,
      note: Option[String] = None,
      intransitive: Boolean = false,
      scalaFilter: Option[String] = None,
      crossVersion: Option[CrossVersion] = None
  ) {

    /** Converts this dependency to an SBT ModuleID for use in libraryDependencies. */
    def toModuleID(sbtBinaryVersion: String, scalaBinaryVersion: String): ModuleID = {
      val defaultCrossVersion: CrossVersion =
        if (dependency.isCross) CrossVersion.binary else CrossVersion.disabled

      dependency
        .toModuleID(sbtBinaryVersion, scalaBinaryVersion)
        .withCrossVersion(crossVersion.getOrElse(defaultCrossVersion))
        .withIsTransitive(!intransitive)
    }

    /** The shape under which the artifact is published, derived from the dependency's configuration and the
      * `cross-version` annotation when present. Drives `VersionFinder` lookups during update operations.
      */
    def kind: ArtifactKind =
      if (dependency.configuration === "sbt-plugin") ArtifactKind.SbtPlugin
      else
        crossVersion match {
          case Some(_: CrossVersion.Full)              => ArtifactKind.CrossFull
          case Some(_: CrossVersion.Patch)             => ArtifactKind.CrossFull
          case Some(_: CrossVersion.Binary)            => ArtifactKind.Cross
          case Some(cv) if cv == CrossVersion.disabled => ArtifactKind.Java // scalafix:ok
          case Some(_)                                 => ArtifactKind.fromDependency(dependency)
          case None if dependency.isCross              => ArtifactKind.Cross
          case None                                    => ArtifactKind.Java
        }

  }

  object Resolved {

    def from(
        annotated: AnnotatedDependency,
        variableResolvers: Map[String, OrganizationArtifactName => ModuleID]
    )(implicit logger: Logger): Resolved = {
      val crossVersion = annotated.crossVersion match {
        case Some("full")     => Some(CrossVersion.full)
        case Some("binary")   => Some(CrossVersion.binary)
        case Some("patch")    => Some(CrossVersion.patch)
        case Some("disabled") => Some(CrossVersion.disabled)
        case Some(other)      => Utils.fail(s"Invalid cross-version: $other")
        case None             => None
      }

      Resolved(
        Dependency.parse(annotated.line, variableResolvers),
        annotated.note,
        annotated.intransitive,
        annotated.scalaFilter,
        crossVersion
      )
    }

  }

  /** Ordering for annotated dependencies: first by configuration, then by organization, then by name.
    *
    * Extracts sort keys directly from the dependency line regex, avoiding the need for full dependency parsing (which
    * requires variable resolvers).
    */
  implicit val AnnotatedDependencyOrdering: Ordering[AnnotatedDependency] = Ordering.by(_.line match {
    case Dependency.dependencyRegex(org, _, name, _, config) =>
      (Option(config).getOrElse("compile"), org.toLowerCase, name.toLowerCase)
    case line =>
      ("zzz", line.toLowerCase, "")
  })

  final case class NoteKey(organization: String, name: String, configuration: String)

  /** Holds the annotation data (note, intransitive flag, scala-filter, cross-version) for a dependency, used during
    * write preservation.
    */
  final case class AnnotationData(
      note: Option[String],
      intransitive: Boolean,
      scalaFilter: Option[String] = None,
      crossVersion: Option[String] = None
  )

  /** Legal values for the `cross-version` annotation. */
  val LegalCrossVersionValues: Set[String] = Set("full", "binary", "patch", "disabled")

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

            // If the value is an object, check for 'dependency' and at least one annotation field
            case ConfigValueType.OBJECT =>
              val obj = value.asInstanceOf[ConfigObject].toConfig
              if (!obj.hasPath("dependency")) Left("object entry must have a 'dependency' field")
              else {
                val dependency     = obj.getString("dependency")
                val note           = if (obj.hasPath("note")) Some(obj.getString("note")) else None
                val isIntransitive = obj.hasPath("intransitive") && obj.getBoolean("intransitive")
                val scalaFilter    = if (obj.hasPath("scala-filter")) Some(obj.getString("scala-filter")) else None
                val crossVersion   = if (obj.hasPath("cross-version")) Some(obj.getString("cross-version")) else None

                if (note.isEmpty && !isIntransitive && scalaFilter.isEmpty && crossVersion.isEmpty)
                  Left("object entry must have a 'note', 'intransitive', 'scala-filter', or 'cross-version' field")
                else if (crossVersion.nonEmpty && !LegalCrossVersionValues.contains(crossVersion.get))
                  Left(s"'cross-version' must be one of ${LegalCrossVersionValues.mkString(", ")}, got '${crossVersion.get}'")
                else
                  Right(acc :+ AnnotatedDependency(dependency, note, isIntransitive, scalaFilter, crossVersion))
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

    AnnotatedDependency(dep.toLine, data.flatMap(_.note), data.exists(_.intransitive), data.flatMap(_.scalaFilter),
      data.flatMap(_.crossVersion))
  }

}
