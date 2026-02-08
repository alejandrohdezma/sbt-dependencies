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

package com.alejandrohdezma.sbt.dependencies

import scala.util.Try

import sbt.Defaults.sbtPluginExtra
import sbt.librarymanagement.CrossVersion
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import sbt.librarymanagement.ModuleID
import sbt.librarymanagement.syntax._
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.Eq._

/** Represents a dependency line from the dependencies file.
  *
  * This is a sealed abstract class with two concrete subtypes:
  *   - [[Dependency.WithNumericVersion]] for dependencies with numeric versions (e.g., `1.2.3`).
  *   - [[Dependency.WithVariableVersion]] for dependencies with variable versions (e.g., `{{myVar}}`).
  */
sealed abstract class Dependency {

  def organization: String

  def name: String

  def version: Dependency.Version

  def isCross: Boolean

  def group: String

  def configuration: String

  override def hashCode: Int = (organization, name, isCross, group, configuration).hashCode // scalafix:ok

  override def equals(other: Any): Boolean = other match { // scalafix:ok
    case other: Dependency =>
      organization === other.organization && name === other.name && isCross === other.isCross &&
      group === other.group && configuration === other.configuration
    case _ => false
  }

  /** Returns a copy of this dependency with the given version. */
  def withVersion(version: Dependency.Version): Dependency = version match {
    case v: Dependency.Version.Numeric =>
      Dependency.WithNumericVersion(organization, name, v, isCross, group, configuration)
    case v: Dependency.Version.Variable =>
      Dependency.WithVariableVersion(organization, name, v, isCross, group, configuration)
  }

  /** Converts this dependency to an SBT ModuleID for use in libraryDependencies. */
  def toModuleID(sbtBinaryVersion: String, scalaBinaryVersion: String): ModuleID = {
    val module = ModuleID(organization, name, version.toVersionString)

    if (configuration === "sbt-plugin")
      sbtPluginExtra(module, sbtBinaryVersion, scalaBinaryVersion)
    else
      module
        .withConfigurations(Some(configuration).filterNot(_ === "compile"))
        .withCrossVersion(if (isCross) CrossVersion.binary else CrossVersion.disabled)
  }

  /** Checks if the dependency is the same artifact as another dependency. */
  def isSameArtifact(other: Dependency): Boolean =
    organization === other.organization && name === other.name && isCross === other.isCross && group === other.group

  /** Finds the latest version for this dependency.
    *
    * For numeric versions, finds the latest version matching the marker constraints. For variable versions, finds the
    * latest stable version (variables always use NoMarker).
    *
    * @return
    *   A [[Dependency.WithNumericVersion]] containing the latest version found.
    */
  def findLatestVersion(implicit versionFinder: Utils.VersionFinder, logger: Logger): Dependency.WithNumericVersion = {
    val latest = Utils.findLatestVersion(organization, name, isCross, configuration === "sbt-plugin", version)
    Dependency.WithNumericVersion(organization, name, latest, isCross, group, configuration)
  }

  /** Converts the dependency to a line. */
  def toLine: String = {
    val configSuffix = if (configuration === "compile") "" else s":$configuration"
    if (isCross) s"$organization::$name:${version.show}$configSuffix"
    else s"$organization:$name:${version.show}$configSuffix"
  }

  /** Extracts the Scala version suffix from the artifact name, if present.
    *
    * @return
    *   Some("3"), Some("2.13"), Some("2.12"), etc. if the name ends with a Scala version suffix, None otherwise.
    */
  def scalaVersionSuffix: Option[String] =
    Dependency.scalaVersionSuffixes.find(suffix => name.endsWith(s"_$suffix"))

  /** Checks if this dependency matches the given Scala binary version.
    *
    * Dependencies without a Scala version suffix always match. Dependencies with a suffix only match if the suffix
    * corresponds to the given Scala binary version.
    *
    * @param scalaBinaryVersion
    *   The Scala binary version to check against (e.g., "2.13", "3").
    * @return
    *   true if this dependency should be included for the given Scala version.
    */
  def matchesScalaVersion(scalaBinaryVersion: String): Boolean =
    scalaVersionSuffix.forall(_ === scalaBinaryVersion)

}

object Dependency {

  final case class WithNumericVersion(
      organization: String,
      name: String,
      version: Version.Numeric,
      isCross: Boolean,
      group: String,
      configuration: String = "compile"
  ) extends Dependency

  final case class WithVariableVersion(
      organization: String,
      name: String,
      version: Version.Variable,
      isCross: Boolean,
      group: String,
      configuration: String = "compile"
  ) extends Dependency

  def unapply(dep: Dependency): Option[(String, String, Version, Boolean, String, String)] =
    Some((dep.organization, dep.name, dep.version, dep.isCross, dep.group, dep.configuration))

  def fromModuleID(moduleID: ModuleID, group: String): Option[Dependency] =
    Version.Numeric.from(moduleID.revision, Version.Numeric.Marker.NoMarker).map { version =>
      // Detect sbt plugins by checking for sbtVersion in extraAttributes
      val isSbtPlugin = moduleID.extraAttributes.contains("e:sbtVersion")

      val configuration =
        if (isSbtPlugin) "sbt-plugin"
        else moduleID.configurations.getOrElse("compile")

      WithNumericVersion(
        moduleID.organization,
        moduleID.name,
        version,
        moduleID.crossVersion != CrossVersion.disabled, // scalafix:ok
        group,
        configuration
      )
    }

  /** Known Scala version suffixes for artifact names */
  val scalaVersionSuffixes: List[String] = List("2.13", "2.12", "2.11", "2.10", "3")

  /** Ordering for dependencies: first by configuration, then by toLine. */
  implicit val DependencyOrdering: Ordering[Dependency] = Ordering.by(d => (d.configuration, d.toLine))

  /** Creates a dependency with the latest stable version resolved from Coursier. */
  def withLatestStableVersion(
      organization: String,
      name: String,
      isCross: Boolean,
      group: String
  )(implicit versionFinder: Utils.VersionFinder, logger: Logger): Dependency = {
    val version =
      Try(Utils.findLatestVersion(organization, name, isCross, false)(_.isStableVersion))
        .getOrElse(Utils.findLatestVersion(organization, name, isCross, true)(_.isStableVersion))

    WithNumericVersion(organization, name, version, isCross, group)
  }

  /** Regex for parsing dependency lines.
    *
    * Supports formats:
    *   - `org::name` (cross-compiled without version)
    *   - `org::name:version` (cross-compiled with version)
    *   - `org::name:version:config` (cross-compiled with version and configuration)
    *   - `org:name` (java without version)
    *   - `org:name:version` (java with version)
    *   - `org:name:version:config` (java with version and configuration)
    *
    * Whitespace around components is automatically trimmed.
    *
    * Groups: (1) organization, (2) separator, (3) name, (4) version?, (5) config?
    */
  val dependencyRegex = """^\s*([^\s:]+)\s*(::?)\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*)?)?$""".r

  /** Parses a dependency line into a dependency */
  def parse(
      line: String,
      group: String,
      variableResolvers: Map[String, OrganizationArtifactName => ModuleID] = Map.empty
  )(implicit versionFinder: Utils.VersionFinder, logger: Logger): Dependency =
    line match {
      case dependencyRegex(org, sep, name, null, _) => // scalafix:ok
        Dependency.withLatestStableVersion(org, name, isCross = sep === "::", group)

      case dependencyRegex(org, sep, name, Version.Variable.regex(variable), config) =>
        variableResolvers
          .get(variable)
          .map(_(if (sep === "::") org %% name else org % name))
          .map(_.revision)
          .flatMap(Version.Numeric.unapply)
          .map(Version.Variable(variable, _))
          .map(WithVariableVersion(org, name, _, sep === "::", group, Option(config).getOrElse("compile")))
          .getOrElse {
            val available =
              if (variableResolvers.isEmpty) "(none defined)"
              else variableResolvers.keys.mkString(", ")
            Utils.fail {
              s"Variable '{{$variable}}' not found in dependencyVersionVariables. Available: $available"
            }
          }

      case dependencyRegex(org, sep, name, Version.Numeric(version), config) =>
        WithNumericVersion(org, name, version, isCross = sep === "::", group, Option(config).getOrElse("compile"))

      case _ =>
        Utils.fail(s"$line is not a valid dependency")
    }

  /** A version specification for a dependency.
    *
    * Can be either a numeric version (e.g., `1.2.3`) or a variable reference (e.g., `{{myVar}}`).
    */
  sealed trait Version {

    /** Full string representation (with marker prefix for numeric, with braces for variable). */
    def show: String

    /** Version string for display purposes (numeric version or resolved version for variables). */
    def toVersionString: String

    /** Checks if a candidate version is valid for this version. */
    def isValidCandidate(candidate: Version.Numeric): Boolean

  }

  object Version {

    /** A numeric version with variable-length parts and optional suffix.
      *
      * Supports formats like:
      *   - `1.2.3` (standard version)
      *   - `1.0` (2-part version)
      *   - `3.2.14.0` (4-part version)
      *   - `4.2.7.Final` (with dot-suffix)
      *   - `1.0.0-rc1` (with hyphen-suffix)
      */
    final case class Numeric(parts: List[Int], suffix: Option[String], marker: Numeric.Marker) extends Version {

      /** Checks if the version is a stable version (3 parts, no suffix). */
      def isStableVersion: Boolean = suffix.isEmpty && parts.length === 3

      /** Checks if the version is the same as another version. */
      def isSameVersion(other: Numeric): Boolean = parts === other.parts && suffix === other.suffix

      /** First numeric part (major version). */
      def major: Int = parts.headOption.getOrElse(0)

      /** Second numeric part (minor version). */
      def minor: Int = parts.lift(1).getOrElse(0)

      /** Version string without marker prefix (for Coursier sorting). */
      def toVersionString: String = parts.mkString(".") + suffix.getOrElse("")

      /** Full string representation with marker prefix. */
      def show: String = s"${marker.prefix}$toVersionString"

      /** Extracts suffix type (letters only, ignoring leading separator and replace any numbers with *). */
      val suffixType: Option[String] = suffix.map(_.toLowerCase.replaceAll("^[.-]", "").replaceAll("\\d", "*"))

      /** Extracts the numeric part from the suffix (e.g., "-rc2" -> Some(2), "-jre" -> None). */
      val suffixNumber: Option[Int] = suffix.flatMap("(\\d+)".r.findFirstIn).map(_.toInt)

      /** Checks if a candidate version is valid for this version. */
      override def isValidCandidate(candidate: Numeric): Boolean = {
        // Must have same number of parts (shape matching)
        val sameShape = parts.length === candidate.parts.length

        // Must have same suffix type
        val sameSuffix = suffixType === candidate.suffixType

        // Must pass marker filter
        val passesMarker = marker.filter(this, candidate)

        sameShape && sameSuffix && passesMarker
      }

    }

    object Numeric {

      implicit val NumericVersionEq: Eq[Numeric] = (a, b) =>
        a.parts === b.parts && a.suffix === b.suffix && a.marker === b.marker

      /** Ordering for versions: compares numeric parts left-to-right, then suffix numbers. */
      implicit val NumericVersionOrdering: Ordering[Numeric] = (v1: Numeric, v2: Numeric) => {
        val maxLen  = math.max(v1.parts.length, v2.parts.length)
        val padded1 = v1.parts.padTo(maxLen, 0)
        val padded2 = v2.parts.padTo(maxLen, 0)

        val partsComparison = padded1.zip(padded2).foldLeft(0) {
          case (0, (p1, p2)) => p1.compareTo(p2)
          case (acc, (_, _)) => acc
        }

        if (partsComparison !== 0) partsComparison
        else v1.suffixNumber.getOrElse(0).compareTo(v2.suffixNumber.getOrElse(0): Int)
      }

      private val regex = """^(\d+(?:\.\d+)*)(.*)$""".r

      /** Parses a version string into a Numeric version with the given marker. */
      def from(string: String, marker: Marker): Option[Numeric] = string match {
        case regex(numericPart, rest) =>
          val parts = numericPart.split('.').map(_.toInt).toList

          val suffix = if (rest.nonEmpty) Some(rest) else None

          Some(Numeric(parts, suffix, marker))

        case _ => None
      }

      /** Parses a version string into a Numeric version. */
      def unapply(version: String): Option[Numeric] =
        if (version.startsWith("=")) Numeric.from(version.drop(1), Marker.Exact)
        else if (version.startsWith("^")) Numeric.from(version.drop(1), Marker.Major)
        else if (version.startsWith("~")) Numeric.from(version.drop(1), Marker.Minor)
        else Numeric.from(version, Marker.NoMarker)

      /** Version pinning marker for controlling update behavior.
        *
        * @param prefix
        *   The prefix character for this marker.
        * @param filter
        *   The filter function to determine if a candidate version is acceptable.
        */
      sealed abstract class Marker(val prefix: String, val filter: (Numeric, Numeric) => Boolean) {

        /** Checks if this marker is an exact marker. */
        def isExact: Boolean = this match {
          case Marker.Exact => true
          case _            => false
        }

      }

      object Marker {

        /** No marker - update to the latest version. */
        case object NoMarker extends Marker("", (_, _) => true)

        /** Pin to exact version - never update. */
        case object Exact extends Marker("=", (_, _) => false)

        /** Pin to major version - update within major only. */
        case object Major extends Marker("^", (a, b) => a.major === b.major)

        /** Pin to minor version - update within minor only. */
        case object Minor extends Marker("~", (a, b) => a.major === b.major && a.minor === b.minor)

        implicit val MarkerEq: Eq[Marker] = (a, b) => a.prefix === b.prefix

      }

    }

    /** A variable-based version that references a resolver defined in build settings.
      *
      * @param name
      *   The variable name (without braces).
      * @param resolved
      *   The resolved numeric version from the resolver function.
      */
    final case class Variable(name: String, resolved: Numeric) extends Version {

      /** Full string representation with braces. */
      def show: String = s"{{$name}}"

      /** Version string for display - returns the resolved version. */
      def toVersionString: String = resolved.toVersionString

      /** Checks if a candidate version is valid for this version. */
      override def isValidCandidate(candidate: Numeric): Boolean =
        resolved.isValidCandidate(candidate)

    }

    object Variable {

      /** Regex for variable references. Only allows alphanumeric characters and underscores. */
      val regex = """\{\{(\w+)\}\}""".r

      implicit val VariableEq: Eq[Variable] = (a, b) => a.name === b.name && a.resolved === b.resolved

    }

  }

}
