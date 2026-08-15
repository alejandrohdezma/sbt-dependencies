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
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.alejandrohdezma.sbt.dependencies.model.Fields
import com.alejandrohdezma.sbt.dependencies.model.Group
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigList
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueType

/** Represents the configuration for a group in the dependencies file. */
sealed trait GroupConfig {

  /** The list of annotated dependencies for this group. */
  def dependencies: List[AnnotatedDependency]

  /** The dependency lines (without notes) for this group. */
  def dependencyLines: List[String] = dependencies.map(_.line)

  /** The list of Scala versions for this group, parsed with their pinning markers. */
  def scalaVersions: List[Numeric] = Nil

  /** Optional Java target version for this group (e.g. `"17"`, `"25"`). When set, controls the bytecode level produced
    * for this group's modules.
    */
  def javaVersion: Option[String] = None

  /** Whether this group renders nothing: no dependencies, no Scala versions and no Java version. */
  def isEmpty: Boolean = dependencies.isEmpty && scalaVersions.isEmpty && javaVersion.isEmpty

  def sorted: GroupConfig = this match {
    case GroupConfig.Simple(deps)           => GroupConfig.Simple(deps.sorted)
    case GroupConfig.Advanced(deps, sv, jv) => GroupConfig.Advanced(deps.sorted, sv, jv)
  }

  /** Formats a group with its configuration for HOCON output. */
  def format(group: Group): String = this match {
    case GroupConfig.Simple(deps) =>
      s"""${group.name} = [\n${deps.map(d => indent(d.format, 2)).mkString("\n")}\n]"""

    case GroupConfig.Advanced(deps, versions, javaVersion) =>
      val javaVersionLines = javaVersion.map(v => s"""  ${Fields.JavaVersion} = "$v"""").toList

      val scalaVersionLines = versions match {
        case Nil           => Nil
        case single :: Nil => List(s"""  ${Fields.ScalaVersion} = "${single.show}"""")
        case multiple      =>
          List(s"""  ${Fields.ScalaVersions} = [${multiple.map(v => s""""${v.show}"""").mkString(", ")}]""")
      }

      val depsLines =
        if (deps.nonEmpty)
          List(s"""  ${Fields.Dependencies} = [\n${deps.map(d => indent(d.format, 4)).mkString("\n")}\n  ]""")
        else Nil

      val sections = javaVersionLines ++ scalaVersionLines ++ depsLines

      val body = if (sections.isEmpty) s"  ${Fields.Dependencies} = []" :: Nil else sections

      s"${group.name} {\n${body.mkString("\n")}\n}"
  }

  private def indent(s: String, n: Int): String = s.linesIterator.map((" " * n) + _).mkString("\n")

}

object GroupConfig {

  /** Renders groups in canonical form: empty groups dropped, groups sorted (`sbt-build`, `common-settings`, then
    * alphabetical) and separated by blank lines. Dependencies are rendered as-is — sort them beforehand via
    * [[GroupConfig.sorted]].
    */
  def render(configs: Iterable[(Group, GroupConfig)]): String =
    configs.toList.filterNot { case (_, config) => config.isEmpty }
      .sortBy(_._1)
      .map { case (group, config) => config.format(group) }
      .mkString("\n\n")

  /** Parses `content` as HOCON and returns its top-level entries as raw values, keeping their origins. The single entry
    * point to the HOCON parser for dependencies documents, shared by [[parseAll]] and [[DependenciesOutline]]. Invalid
    * HOCON syntax throws `ConfigException`, like `ConfigFactory.parseString` does.
    */
  private[io] def rootEntries(content: String): List[(String, ConfigValue)] =
    ConfigFactory.parseString(content).root().entrySet().asScala.toList.map(entry => entry.getKey -> entry.getValue)

  /** Parses a whole dependencies file into its groups. Empty content yields an empty map; the first group that fails to
    * parse yields a `Left` naming it. Invalid HOCON syntax throws `ConfigException`, like `ConfigFactory.parseString`
    * does.
    */
  def parseAll(content: String): Either[String, Map[Group, GroupConfig]] =
    rootEntries(content).foldRight[Either[String, Map[Group, GroupConfig]]](Right(Map.empty)) {
      case ((name, value), acc) =>
        for {
          groups <- acc
          group   = Group(name)
          parsed <- parse(value, group).left.map(error => s"Failed to parse group `$name`: $error")
        } yield groups + (group -> parsed)
    }

  /** Parses a group from its raw HOCON value, detecting whether it's simple or advanced format. */
  def parse(value: ConfigValue, group: Group): Either[String, GroupConfig] =
    value.valueType() match {
      case ConfigValueType.LIST =>
        AnnotatedDependency.parse(value.asInstanceOf[ConfigList]).flatMap(checkDuplicates).map(GroupConfig.Simple(_))

      case ConfigValueType.OBJECT =>
        val groupConfig = value.asInstanceOf[ConfigObject].toConfig

        val sbtBuildOnlyDependencies =
          List(Fields.ScalaVersion, Fields.ScalaVersions, Fields.JavaVersion)
            .find(groupConfig.hasPath(_) && group === Group.`sbt-build`)
            .toLeft(())
            .left
            .map { key =>
              s"`sbt-build` cannot define `$key`. Move it to the `common-settings` group " +
                "(build-wide default) or to a per-project group (project-specific value)."
            }

        val dependencies =
          if (groupConfig.hasPath(Fields.Dependencies))
            groupConfig.getValue(Fields.Dependencies) match {
              case list: ConfigList => AnnotatedDependency.parse(list).flatMap(checkDuplicates)
              case other            => Left(s"'dependencies' must be a list, got ${other.valueType()}")
            }
          else Right(Nil)

        val scalaVersions =
          (groupConfig.hasPath(Fields.ScalaVersions), groupConfig.hasPath(Fields.ScalaVersion)) match {
            case (true, true) =>
              Left("Only one of 'scala-versions' or 'scala-version' can be present")
            case (true, _) =>
              groupConfig.getValue(Fields.ScalaVersions).valueType() match {
                case ConfigValueType.LIST =>
                  val list = groupConfig.getStringList(Fields.ScalaVersions).asScala.toList
                  if (list.isEmpty) Left("'scala-versions' cannot be empty")
                  else parseScalaVersions(list)
                case other => Left(s"'scala-versions' must be a list, got $other")
              }
            case (false, true) =>
              groupConfig.getValue(Fields.ScalaVersion).valueType() match {
                case ConfigValueType.STRING => parseScalaVersions(List(groupConfig.getString(Fields.ScalaVersion)))
                case other                  => Left(s"'scala-version' must be a string, got $other")
              }
            case (false, false) => Right(Nil)
          }

        val javaVersion: Either[String, Option[String]] =
          if (groupConfig.hasPath(Fields.JavaVersion))
            groupConfig.getValue(Fields.JavaVersion).valueType() match {
              case ConfigValueType.STRING => Right(Some(groupConfig.getString(Fields.JavaVersion)))
              case other                  => Left(s"'java-version' must be a string, got $other")
            }
          else Right(None)

        for {
          _    <- sbtBuildOnlyDependencies
          deps <- dependencies
          sv   <- scalaVersions
          jv   <- javaVersion
        } yield Advanced(deps, sv, jv)

      case other =>
        Left(s"expected list or object, got $other")
    }

  /** Parses raw Scala-version strings into [[Numeric]]s, preserving their pinning markers, and fails on the first one
    * that isn't a valid version. Parsing at read time keeps [[GroupConfig.scalaVersions]] honestly typed and surfaces a
    * malformed version as a structural conf error rather than silently dropping it.
    */
  private def parseScalaVersions(raw: List[String]): Either[String, List[Numeric]] =
    raw.foldRight[Either[String, List[Numeric]]](Right(Nil)) { (version, acc) =>
      for {
        versions <- acc
        parsed   <- Numeric.unapply(version).toRight(s"Invalid Scala version: $version")
      } yield parsed :: versions
    }

  /** Rejects two entries that share the same `(organization, name, configuration)`. The merge in
    * `DependenciesFile.write` keys on this triple, so duplicates would silently lose the second entry; failing at parse
    * time surfaces the user error instead.
    */
  private def checkDuplicates(deps: List[AnnotatedDependency]): Either[String, List[AnnotatedDependency]] = {
    val keys = deps.flatMap { ad =>
      ad.line match {
        case Dependency.dependencyRegex(org, _, name, _, config) =>
          List((org, name, Option(config).getOrElse("compile")))
        case _ => Nil
      }
    }
    val duplicates = keys.groupBy(identity).collect { case (k, vs) if vs.size > 1 => k }.toList
    if (duplicates.isEmpty) Right(deps)
    else
      Left(
        "duplicate dependency entries: " +
          duplicates.map { case (o, n, c) => s"$o:$n ($c)" }.mkString(", ")
      )
  }

  /** Simple format: just a list of dependencies.
    *
    * HOCON representation:
    * {{{
    * my-project = [
    *   "org::name:version"
    *   { dependency = "org2::name2:^version2", note = "Reason for pinning" }
    * ]
    * }}}
    */
  final case class Simple(dependencies: List[AnnotatedDependency]) extends GroupConfig

  /** Advanced format: an object with dependencies and potentially other fields.
    *
    * HOCON representation:
    * {{{
    * my-project {
    *   java-version = "25"
    *   scala-versions = ["2.13.12", "2.12.18"]
    *   dependencies = [
    *     "org::name:version"
    *     { dependency = "org2::name2:^version2", note = "Reason for pinning" }
    *   ]
    * }
    * }}}
    */
  final case class Advanced(
      dependencies: List[AnnotatedDependency],
      override val scalaVersions: List[Numeric] = Nil,
      override val javaVersion: Option[String] = None
  ) extends GroupConfig

}
