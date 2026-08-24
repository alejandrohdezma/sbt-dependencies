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

package com.alejandrohdezma.sbt.dependencies.model

import scala.util.Try

import com.alejandrohdezma.sbt.dependencies.model.Eq._

/** A dependency entry: an artifact coordinate (`organization`, `name`, `version`) plus how it should be wired into the
  * build (`crossVersion`, `configuration`) and any optional annotations (`note`, `intransitive`, `scalaFilter`).
  *
  * `version` is a sealed `Version` — either `Numeric` (a real version like `1.2.3`) or `Variable` (a `{{name}}`
  * placeholder, possibly carrying a resolved `Numeric` once the build's `dependencyVersionVariables` resolver was
  * applied at the read seam).
  *
  * `crossVersion` is the cross-compilation shape from the `cross-version` annotation. `Cross.Disabled` means a
  * Java-style line (`org:name`); anything else means a cross-compiled line (`org::name`), with `Binary` being the
  * implicit default for `::` and `Full`/`Patch` requiring an explicit `cross-version` annotation.
  */
final case class Dependency(
    organization: String,
    name: String,
    version: Dependency.Version,
    configuration: String = "compile",
    note: Option[String] = None,
    intransitive: Boolean = false,
    scalaFilter: Option[String] = None,
    crossVersion: Dependency.Cross = Dependency.Cross.Disabled
) {

  /** Whether this dep is cross-compiled. Derived from `crossVersion`: anything other than `Disabled` is cross. */
  def isCross: Boolean = crossVersion !== Dependency.Cross.Disabled

  /** Returns a copy of this dependency with the given organization. */
  def withOrganization(organization: String): Dependency = copy(organization = organization)

  /** Returns a copy of this dependency with the given artifact name. */
  def withName(name: String): Dependency = copy(name = name)

  /** Returns a copy of this dependency with the given version. Annotations are preserved. */
  def withVersion(version: Dependency.Version): Dependency = copy(version = version)

  /** Returns a copy of this dependency with the given annotation set, preserving organization/name/version/etc. */
  def withAnnotations(
      note: Option[String],
      intransitive: Boolean,
      scalaFilter: Option[String],
      crossVersion: Dependency.Cross
  ): Dependency = copy(
    note = note,
    intransitive = intransitive,
    scalaFilter = scalaFilter,
    crossVersion = crossVersion
  )

  /** Checks if the dependency is the same artifact as another dependency. Cross vs Java is part of the artifact
    * identity, but `binary`/`full`/`patch` (all cross-compiled) are not — they all map to the same Maven coordinate.
    */
  def isSameArtifact(other: Dependency): Boolean =
    organization === other.organization && name === other.name && isCross === other.isCross &&
      configuration === other.configuration

  /** Converts the dependency to a line. The separator is `::` for cross-compiled deps and `:` only when
    * `crossVersion == Cross.Disabled`.
    */
  def toLine: String = {
    val configSuffix = if (configuration === "compile") "" else s":$configuration"
    val sep          = if (isCross) "::" else ":"
    s"$organization$sep$name:${version.show}$configSuffix"
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

  def scala(current: Version.Numeric) =
    Dependency(
      organization = "org.scala-lang",
      name = if (current.major === 3 && current.minor < 8) "scala3-library_3" else "scala-library",
      version = current,
      crossVersion = Cross.Disabled
    )

  def scalafmt(current: Version.Numeric) =
    Dependency(
      organization = "org.scalameta",
      name = "scalafmt-core",
      version = current,
      crossVersion = Cross.Binary
    )

  def sbt(current: Version.Numeric) =
    Dependency(
      organization = "org.scala-sbt",
      name = "sbt",
      version = current,
      crossVersion = Cross.Disabled
    )

  /** The cross-compilation shape from the `cross-version` annotation. Mirrors the SBT `CrossVersion` variants the file
    * format supports; mapping to/from the actual SBT type happens at the plugin seam.
    */
  sealed abstract class Cross(val keyword: String)

  object Cross {

    case object Binary extends Cross("binary")

    case object Full extends Cross("full")

    case object Patch extends Cross("patch")

    case object Disabled extends Cross("disabled")

    /** Parses a `cross-version` annotation keyword. */
    def fromKeyword(keyword: String): Option[Cross] =
      List(Binary, Full, Patch, Disabled).find(_.keyword === keyword)

    implicit val CrossEq: Eq[Cross] = (a, b) => a.keyword === b.keyword

  }

  /** Maps a [[Cross]] to the keyword used by the `cross-version` annotation. */
  def crossVersionKeyword(cross: Cross): Option[String] = Some(cross.keyword)

  /** The literal SBT configuration string used by `addCompilerPlugin` to mark a compiler plugin dependency. */
  val CompilerPluginConfiguration: String = "plugin->default(compile)"

  /** Known Scala version suffixes for artifact names */
  val scalaVersionSuffixes: List[String] = List("2.13", "2.12", "2.11", "2.10", "3")

  /** Ordering for dependencies: first by configuration, then by organization, then by name. */
  implicit val DependencyOrdering: Ordering[Dependency] = Ordering.by { d =>
    (d.configuration, d.organization.toLowerCase, d.name.toLowerCase)
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

  /** Returns true if the given string looks like a numeric version (`1.2.3`, `=1.0`, `~2.x.y`), a variable reference
    * (`{{name}}`) or a BOM-managed version (`*`). Used to disambiguate the `version` slot from a `config` slot when the
    * version is missing.
    */
  def looksLikeVersion(s: String): Boolean =
    Version.Numeric.unapply(s).isDefined || Version.Variable.regex.findFirstMatchIn(s).isDefined || s === "*"

  /** Returns an error when a BOM-managed version (`*`) is combined with something it cannot work with: the `bom`
    * configuration (a BOM coordinate cannot take its version from a BOM), the `sbt-plugin` configuration (BOMs cannot
    * pin sbt plugin coordinates) or a `full`/`patch` cross-version (BOM entries are looked up by binary suffix). Called
    * from the read seam and from `install`, so invalid lines are rejected before they ever reach the file.
    */
  def validateBomRestrictions(dependency: Dependency): Either[String, Unit] =
    dependency.version match {
      case Version.Bom(_) if dependency.configuration === "bom" =>
        Left {
          s"${dependency.organization}:${dependency.name} declares version '*' with the 'bom' configuration — " +
            "a BOM coordinate cannot take its version from a BOM."
        }

      case Version.Bom(_) if dependency.configuration === "sbt-plugin" =>
        Left {
          s"${dependency.organization}:${dependency.name} declares version '*' with the 'sbt-plugin' configuration" +
            " — BOMs cannot pin sbt plugin coordinates."
        }

      case Version.Bom(_) if !List[Cross](Cross.Binary, Cross.Disabled).contains(dependency.crossVersion) =>
        Left {
          s"Version '*' on ${dependency.organization}:${dependency.name} cannot be combined with " +
            s"cross-version = '${dependency.crossVersion.keyword}' — " +
            "only 'binary' and 'disabled' are supported when the version comes from a BOM."
        }

      case _ => Right(())
    }

  /** Parses a dependency line into a dependency.
    *
    * Variables (`{{name}}`) are produced unresolved (`Variable(name, None)`) — resolution happens later via
    * `resolveVariable` at the SBT seam, where the dep's final `crossVersion` (after annotation merge) is known and can
    * be passed to the resolver function. BOM-managed versions (`*`) are likewise produced unresolved (`Bom(None)`) —
    * resolution happens via `resolveBom` once the group's flattened BOM pins are available.
    */
  def parse(line: String): Either[String, Dependency] =
    line match {
      case dependencyRegex(_, _, _, null, _) =>
        Left(s"$line is missing a version")

      case dependencyRegex(org, sep, name, "*", config) =>
        Right(
          Dependency(
            org,
            name,
            Version.Bom(None),
            configuration = Option(config).getOrElse("compile"),
            crossVersion = if (sep === "::") Cross.Binary else Cross.Disabled
          )
        )

      case dependencyRegex(org, sep, name, Version.Variable.regex(variable), config) =>
        Right(
          Dependency(
            org,
            name,
            Version.Variable(variable, None),
            configuration = Option(config).getOrElse("compile"),
            crossVersion = if (sep === "::") Cross.Binary else Cross.Disabled
          )
        )

      case dependencyRegex(org, sep, name, Version.Numeric(version), config) =>
        Right(
          Dependency(
            org,
            name,
            version,
            configuration = Option(config).getOrElse("compile"),
            crossVersion = if (sep === "::") Cross.Binary else Cross.Disabled
          )
        )

      case _ =>
        Left(s"$line is not a valid dependency")
    }

  /** A version specification for a dependency.
    *
    * Can be a numeric version (e.g., `1.2.3`), a variable reference (e.g., `{{myVar}}`) or a BOM-managed version (`*`,
    * resolved from the group's `bom`-configured entries).
    */
  sealed trait Version {

    override def toString(): String = toVersionString

    /** Full string representation (with marker prefix for numeric, with braces for variable). */
    def show: String

    /** Version string for display purposes (numeric version or resolved version for variables). */
    def toVersionString: String

    /** Checks if a candidate version is valid for this version. */
    def isValidCandidate(candidate: Version.Numeric): Boolean

    /** Checks if the version is the same as another version. Returns `true` only if they hold the same numeric value. */
    def isSameVersion(version: Version): Boolean

    /** Checks if the version is a variable. */
    def isVariable: Boolean

    def isBom: Boolean

  }

  object Version {

    def unapply(dependency: Dependency): Option[Version] = Some(dependency.version)

    implicit val VersionEq: Eq[Version] = {
      case (a: Numeric, b: Numeric)   => a.parts === b.parts && a.suffix === b.suffix && a.marker === b.marker
      case (a: Variable, b: Variable) =>
        val resolvedSame = (a.resolved, b.resolved) match {
          case (Some(x), Some(y)) => VersionEq.eqv(x, y)
          case (None, None)       => true
          case _                  => false
        }
        a.name === b.name && resolvedSame
      case (a: Bom, b: Bom) =>
        (a.resolved, b.resolved) match {
          case (Some(x), Some(y)) => VersionEq.eqv(x, y)
          case (None, None)       => true
          case _                  => false
        }
      case _ => false
    }

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

      /** Returns a copy of this version with the given marker, preserving parts and suffix. */
      def withMarker(marker: Numeric.Marker): Numeric = copy(marker = marker)

      /** Checks if the version is a stable version (3 parts, no suffix). */
      def isStableVersion: Boolean = suffix.isEmpty && parts.length === 3

      override def isSameVersion(other: Version): Boolean = other match {
        case n: Numeric => parts === n.parts && suffix === n.suffix
        case _          => false
      }

      override def isVariable: Boolean = false

      override def isBom: Boolean = false

      /** First numeric part (major version). */
      def major: Int = parts.headOption.getOrElse(0)

      /** Second numeric part (minor version). */
      def minor: Int = parts.lift(1).getOrElse(0)

      /** Version string without marker prefix (for Coursier sorting). */
      def toVersionString: String = parts.mkString(".") + suffix.getOrElse("")

      /** Full string representation with marker prefix. */
      def show: String = s"${marker.prefix}$toVersionString"

      /** Extracts suffix type (letters only, ignoring leading separator and replace any numbers with *). */
      lazy val suffixType: Option[String] = suffix.map(_.toLowerCase.replaceAll("^[.-]", "").replaceAll("\\d", "*"))

      /** Extracts the numeric part from the suffix (e.g., "-rc2" -> Some(2), "-jre" -> None).
        *
        * Uses `BigInt` so arbitrarily-long digit runs (timestamps like `-20260328142033-SNAPSHOT`, or anything else
        * that exceeds `Long`) parse and order correctly without overflow.
        */
      lazy val suffixNumber: Option[BigInt] = suffix.flatMap("(\\d+)".r.findFirstIn).map(BigInt(_))

      /** Checks if a candidate version is valid for this version. */
      override def isValidCandidate(candidate: Numeric): Boolean = {
        // Must have same number of parts (shape matching)
        val sameShape = parts.length === candidate.parts.length

        // Must have same suffix type
        val sameSuffix = suffixType === candidate.suffixType

        // Must pass marker filter
        val passesMarker = marker.filter(this, candidate)

        // Must be greater than or equal to current version
        val isNewerOrEqual = Ordering[Numeric].gteq(candidate, this)

        sameShape && sameSuffix && passesMarker && isNewerOrEqual
      }

    }

    object Numeric {

      implicit val NumericEq: Eq[Numeric] = (a, b) => VersionEq.eqv(a, b)

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
        else v1.suffixNumber.getOrElse(BigInt(0)).compareTo(v2.suffixNumber.getOrElse(BigInt(0)))
      }

      private val regex = """^(\d+(?:\.\d+)*)(.*)$""".r

      /** Parses a version string into a Numeric version with the given marker.
        *
        * Returns `None` if the string does not match the version regex or if any numeric part overflows `Int`.
        * Returning `None` rather than throwing matters because callers like `VersionFinder` use this through `unapply`
        * inside `collect`, where a thrown exception would discard the entire list of available versions.
        */
      def from(string: String, marker: Marker): Option[Numeric] = string match {
        case regex(numericPart, rest) =>
          Try {
            val parts  = numericPart.split('.').map(_.toInt).toList
            val suffix = if (rest.nonEmpty) Some(rest) else None
            Numeric(parts, suffix, marker)
          }.toOption

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
      *   The resolved numeric version from the resolver function. `None` when the variable was parsed without a
      *   matching entry in `dependencyVersionVariables` — e.g. during `format()` round-trips that don't carry resolver
      *   context. Paths that need a concrete version (`toModuleID`, version-finding) require this to be defined.
      */
    final case class Variable(name: String, resolved: Option[Numeric]) extends Version {

      /** Full string representation with braces. */
      def show: String = s"{{$name}}"

      /** Resolved version string. Throws if the variable hasn't been resolved — callers reaching this without
        * resolution have bypassed the read seam where resolution is enforced.
        */
      def toVersionString: String = resolved match {
        case Some(num) => num.toVersionString
        case None      => sys.error(s"Variable {{$name}} accessed before resolution")
      }

      override def isVariable: Boolean = true

      override def isBom: Boolean = false

      override def isSameVersion(other: Version): Boolean = other match {
        case n: Numeric => resolved.exists(_.isSameVersion(n))
        case _          => false
      }

      /** Checks if a candidate version is valid for this version. Always `false` for unresolved variables. */
      override def isValidCandidate(candidate: Numeric): Boolean =
        resolved.exists(_.isValidCandidate(candidate))

    }

    object Variable {

      implicit val VariableEq: Eq[Variable] = (a, b) => VersionEq.eqv(a, b)

      /** Regex for variable references. Only allows alphanumeric characters and underscores. */
      val regex = """\{\{(\w+)\}\}""".r

    }

    /** A version taken from the group's BOMs (`*` in the file).
      *
      * @param resolved
      *   The numeric version pinned by the first BOM in the group that manages this artifact. `None` when the `*` was
      *   parsed without BOM context — e.g. during `format()` round-trips or inside `dependenciesFromBom`'s own read.
      *   Paths that need a concrete version (`toModuleID`, version-finding) require this to be defined.
      */
    final case class Bom(resolved: Option[Numeric]) extends Version {

      /** Full string representation: always the `*` placeholder. */
      def show: String = "*"

      /** Resolved version string. Throws if the version hasn't been resolved — callers reaching this without resolution
        * have bypassed the seam where BOM resolution is enforced.
        */
      def toVersionString: String = resolved match {
        case Some(num) => num.toVersionString
        case None      => sys.error("BOM-managed version (*) accessed before resolution")
      }

      override def isVariable: Boolean = false

      override def isBom: Boolean = true

      override def isSameVersion(other: Version): Boolean = other match {
        case n: Numeric => resolved.exists(_.isSameVersion(n))
        case _          => false
      }

      /** Checks if a candidate version is valid for this version. Always `false` for unresolved BOM versions. */
      override def isValidCandidate(candidate: Numeric): Boolean =
        resolved.exists(_.isValidCandidate(candidate))

    }

    object Bom {

      implicit val BomEq: Eq[Bom] = (a, b) => VersionEq.eqv(a, b)

    }

  }

}
