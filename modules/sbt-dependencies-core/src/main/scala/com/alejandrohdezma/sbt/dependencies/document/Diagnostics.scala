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

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Entry
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Group
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span
import com.alejandrohdezma.sbt.dependencies.model.Dependency

/** A problem found in a `dependencies.conf` document, positioned at the text it refers to. */
final case class Diagnostic(span: Span, message: String, severity: Diagnostic.Severity)

object Diagnostic {

  /** How severe a [[Diagnostic]] is: errors would make the SBT plugin fail, warnings are suspicious but valid. */
  sealed trait Severity

  object Severity {

    case object Error extends Severity

    case object Warning extends Severity

  }

}

/** Validates a positioned document, reusing the core validators (`Dependency.parse`,
  * `Dependency.validateBomRestrictions`...) so editor diagnostics carry the same messages the SBT plugin itself would
  * fail with.
  */
object Diagnostics {

  /** Returns every problem in the document, in text order per group. */
  def check(document: DependenciesDocument): List[Diagnostic] = document.groups.flatMap(check)

  private def check(group: Group): List[Diagnostic] =
    group.entries.flatMap(check) ++ duplicates(group)

  private def check(entry: Entry): List[Diagnostic] = entry match {
    case line: Entry.DependencyLine => dependency(line.content, line.contentSpan, crossVersion = None).toList

    case obj: Entry.DependencyObject =>
      val missingDependency =
        if (obj.dependency.isEmpty)
          Some(Diagnostic(obj.span, "object entry must have a 'dependency' field", Diagnostic.Severity.Error))
        else None

      val missingAnnotations =
        if (
          obj.dependency.isDefined && obj.note.isEmpty && !obj.intransitive && !obj.overrides &&
          obj.scalaFilter.isEmpty && obj.crossVersion.isEmpty
        )
          Some(
            Diagnostic(
              obj.span,
              "object entry must have a 'note', 'intransitive', 'overrides', 'scala-filter', or 'cross-version' field",
              Diagnostic.Severity.Error
            )
          )
        else None

      val invalidCrossVersion =
        obj.crossVersion.filter(field => Dependency.Cross.fromKeyword(field.value).isEmpty).map { field =>
          Diagnostic(
            field.valueSpan,
            s"'cross-version' must be one of full, binary, patch, disabled, got '${field.value}'",
            Diagnostic.Severity.Error
          )
        }

      val dependencyDiagnostic = obj.dependency.flatMap { field =>
        dependency(field.value, field.valueSpan, obj.crossVersion.flatMap(f => Dependency.Cross.fromKeyword(f.value)))
      }

      List(missingDependency, missingAnnotations, invalidCrossVersion, dependencyDiagnostic).flatten
  }

  /** Validates a dependency line, applying the entry's `cross-version` annotation (like the SBT plugin's read seam
    * does) before checking the BOM and variable restrictions.
    */
  private def dependency(content: String, span: Span, crossVersion: Option[Dependency.Cross]): Option[Diagnostic] = {
    def error(message: String) = Diagnostic(span, message, Diagnostic.Severity.Error)

    if (content.trim.isEmpty) Some(error("Empty dependency string"))
    else if (content.contains("{{") && !content.contains("}}"))
      Some(error("Unclosed variable reference: missing \"}}\""))
    else
      Dependency.parse(content) match {
        case Left(message) => Some(error(message))
        case Right(parsed) =>
          val dep = crossVersion.fold(parsed)(cross =>
            parsed.withAnnotations(parsed.note, parsed.intransitive, parsed.scalaFilter, cross, parsed.overrides)
          )

          val supportedInVariable = List[Dependency.Cross](Dependency.Cross.Binary, Dependency.Cross.Disabled)

          if (dep.version.isVariable && !supportedInVariable.contains(dep.crossVersion))
            Some(error {
              s"Variable '${dep.version.show}' on ${dep.organization}:${dep.name} cannot be combined with " +
                s"cross-version = '${dep.crossVersion.keyword}' — " +
                "only 'binary' and 'disabled' are supported when the version is a variable."
            })
          else Dependency.validateBomRestrictions(dep).left.toOption.map(error)
      }
  }

  /** Flags every repetition of an `(organization, name, configuration)` key already seen in the group, mirroring the
    * key `GroupConfig.checkDuplicates` fails on — but per-entry and as a warning, so editors can point at the exact
    * repeated entry.
    */
  private def duplicates(group: Group): List[Diagnostic] = {
    val keyed = group.entries.flatMap(entry => entry.dependency.map(entry -> _)).flatMap { case (entry, field) =>
      field.value match {
        case Dependency.dependencyRegex(org, _, name, _, config) =>
          List((entry, field, (org, name, Option(config).getOrElse("compile"))))
        case _ => Nil
      }
    }

    keyed.groupBy { case (_, _, key) => key }.collect {
      case ((org, name, config), entries) if entries.lengthCompare(1) > 0 =>
        entries.drop(1).map { case (_, field, _) =>
          Diagnostic(
            field.valueSpan,
            s"duplicate dependency entry: $org:$name ($config)",
            Diagnostic.Severity.Warning
          )
        }
    }.toList.flatten
      .sortBy(_.span.start)
  }

}
