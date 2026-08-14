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

import sbt.Defaults.sbtPluginExtra
import sbt.librarymanagement.CrossVersion
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import sbt.librarymanagement.ModuleID
import sbt.librarymanagement._
import sbt.librarymanagement.syntax._
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.finders.Finders
import com.alejandrohdezma.sbt.dependencies.finders.Utils
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Cross
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version
import com.alejandrohdezma.sbt.dependencies.model.Dependency.Version.Numeric
import com.alejandrohdezma.sbt.dependencies.model.Eq._

/** The SBT seam for the (sbt-free) [[Dependency]] model: conversions to/from SBT types, Coursier-backed version lookups
  * and everything else that needs the sbt API.
  */
object DependencyOps {

  implicit class CrossOps(private val cross: Cross) extends AnyVal {

    /** Maps this cross-compilation shape to the SBT `CrossVersion` applied to resolved `ModuleID`s. */
    def toSbt: CrossVersion = cross match {
      case Cross.Binary   => CrossVersion.binary
      case Cross.Full     => CrossVersion.full
      case Cross.Patch    => CrossVersion.patch
      case Cross.Disabled => CrossVersion.disabled
    }

  }

  implicit class CrossCompanionOps(private val self: Cross.type) extends AnyVal {

    /** Maps an SBT `CrossVersion` to the shape supported by the `cross-version` annotation. Shapes with no keyword
      * (e.g. `Constant`) are cross-compiled in practice, so they map to `Binary` — same information loss as writing
      * them to the file and reading them back.
      */
    def fromSbt(crossVersion: CrossVersion): Cross = crossVersion match {
      case _: Disabled => Cross.Disabled
      case _: Full     => Cross.Full
      case _: Patch    => Cross.Patch
      case _           => Cross.Binary
    }

  }

  implicit class DependencySbtOps(private val dependency: Dependency) extends AnyVal {

    /** When this dependency's version is an unresolved Variable and `resolvers` contains its name, looks the name up
      * and replaces the `Variable` with one that carries the resolved `Numeric`. Resolvers without a matching entry
      * leave the variable unresolved (the read seam reports the error).
      *
      * The `OrganizationArtifactName` passed to the resolver is built from this dep's *final* `isCross` (cross vs Java)
      * — meaningful when the `cross-version` annotation has overridden what the line's separator would imply.
      * Resolution lives here, not inside `Dependency.parse`, because at parse time we only see the line; the annotation
      * is applied later via `withAnnotations`. Note that sbt's `OrganizationArtifactName` constructor is
      * `private[sbt]`, so the OAN can only carry `Binary` (cross) or `Disabled` (Java) — resolvers don't see the
      * `Full`/`Patch` distinction.
      */
    def resolveVariable(
        resolvers: Map[String, OrganizationArtifactName => ModuleID]
    ): Dependency = dependency.version match {
      case Version.Variable(variable, None) =>
        val orgArtifact =
          if (dependency.isCross) dependency.organization %% dependency.name
          else dependency.organization                     % dependency.name
        val resolved = resolvers
          .get(variable)
          .map(_(orgArtifact))
          .map(_.revision)
          .flatMap(Version.Numeric.unapply)
        dependency.withVersion(Version.Variable(variable, resolved))
      case _ => dependency
    }

    /** When this dependency's version is an unresolved BOM version (`*`) and `pins` contains its concrete artifact,
      * replaces the `Bom` with one that carries the pinned `Numeric`. The concrete artifact name is the dep's name
      * suffixed with `scalaBinaryVersion` for cross-compiled deps (BOM entries always carry concrete, already-suffixed
      * artifact names) and the plain name for Java ones. The first matching pin wins, so `pins` must be ordered by BOM
      * declaration (Maven's import semantics: the first-declared BOM takes precedence).
      *
      * Pins without a matching entry leave the version unresolved — the consuming seam reports the error, so round-trip
      * paths can tolerate `Bom(None)`. A matching pin whose version cannot be parsed fails right away.
      */
    def resolveBom(pins: Seq[ModuleID], scalaBinaryVersion: String)(implicit logger: Logger): Dependency =
      dependency.version match {
        case Version.Bom(None) =>
          val artifact = if (dependency.isCross) s"${dependency.name}_$scalaBinaryVersion" else dependency.name

          pins.find(pin => pin.organization === dependency.organization && pin.name === artifact) match {
            case None      => dependency
            case Some(pin) =>
              Version.Numeric.unapply(pin.revision) match {
                case Some(numeric) => dependency.withVersion(Version.Bom(Some(numeric)))
                case None          =>
                  Utils.fail(
                    s"BOM version '${pin.revision}' for ${dependency.organization}:$artifact is not a valid version"
                  )
              }
          }
        case _ => dependency
      }

    /** Converts this dependency to an SBT ModuleID for use in libraryDependencies.
      *
      * The `compiler-plugin` configuration is mapped to `plugin->default(compile)` (what `addCompilerPlugin` produces).
      * Applies the `intransitive` flag and `crossVersion` directly. `sbt-plugin` dependencies keep whatever
      * `sbtPluginExtra` decides: on sbt 1 plugin-ness travels as Ivy extra attributes, but on sbt 2 it IS the
      * cross-version (`name_sbt2_3`), so overriding it with the parsed `crossVersion` would break resolution there.
      */
    def toModuleID(sbtBinaryVersion: String, scalaBinaryVersion: String): ModuleID = {
      val module = ModuleID(dependency.organization, dependency.name, dependency.version.toVersionString)

      val withConfig = dependency.configuration match {
        case "sbt-plugin" =>
          sbtPluginExtra(module, sbtBinaryVersion, scalaBinaryVersion)

        case "compiler-plugin" =>
          module
            .withConfigurations(Some(Dependency.CompilerPluginConfiguration))
            .withCrossVersion(dependency.crossVersion.toSbt)

        case other =>
          module
            .withConfigurations(Some(other).filterNot(_ === "compile"))
            .withCrossVersion(dependency.crossVersion.toSbt)
      }

      withConfig.withIsTransitive(!dependency.intransitive)
    }

    /** Finds the latest version for this dependency.
      *
      * For numeric versions, finds the latest version matching the marker constraints. For variable versions, finds the
      * latest stable version (variables always use NoMarker).
      *
      * @return
      *   A `Dependency` with `version: Version.Numeric` containing the latest version found.
      */
    def findLatestVersion(implicit
        finders: Finders,
        logger: Logger
    ): Dependency =
      Utils.findLatestVersion(dependency)

  }

  implicit class DependencyCompanionOps(private val self: Dependency.type) extends AnyVal {

    def fromModuleID(moduleID: ModuleID): Option[Dependency] = {
      val version: Option[Version] =
        if (moduleID.revision === "*") Some(Version.Bom(None))
        else Version.Numeric.from(moduleID.revision, Version.Numeric.Marker.NoMarker)

      version.map { version =>
        // Detect sbt plugins by checking for sbtVersion in extraAttributes
        val isSbtPlugin = moduleID.extraAttributes.contains("e:sbtVersion")
        // Detect compiler plugins by their configuration string (as set by `addCompilerPlugin`)
        val isCompilerPlugin = moduleID.configurations.contains(Dependency.CompilerPluginConfiguration)

        val configuration =
          if (isSbtPlugin) "sbt-plugin"
          else if (isCompilerPlugin) "compiler-plugin"
          else moduleID.configurations.getOrElse("compile")

        Dependency(moduleID.organization, moduleID.name, version, configuration,
          crossVersion = Cross.fromSbt(moduleID.crossVersion))
      }
    }

    /** Creates a dependency with the latest stable version resolved from Coursier.
      *
      * For `sbt-plugin`, the lookup uses the sbt-plugin artifact shape. For `compiler-plugin` with `isCross`, the
      * lookup queries both the full and binary cross-version shapes and picks the higher version — this finds the
      * actual latest regardless of how the plugin is currently published (e.g. `kind-projector` switched from binary to
      * per-patch around 0.13.0; `better-monadic-for` is binary-only). On a tie, the full shape wins. For other
      * configurations the lookup uses the regular shape and falls back to the sbt-plugin shape if the regular shape
      * returns nothing.
      *
      * The returned `Dependency` carries the `crossVersion` corresponding to whichever shape resolved successfully —
      * `Cross.Full` for compiler-plugins resolved per-patch, `Cross.Binary` for cross-compiled deps resolved
      * per-binary, `Cross.Disabled` for Java deps. Downstream HOCON I/O reads `crossVersion` directly (no separate
      * annotation step needed).
      */
    def withLatestStableVersion(
        organization: String,
        name: String,
        isCross: Boolean,
        configuration: String = "compile"
    )(implicit finders: Finders, logger: Logger): Dependency = {
      val (resolvedCrossVersion, version) = configuration match {
        case "sbt-plugin" =>
          // sbt-plugin queries don't actually use crossVersion (the shape is fixed); keep `Disabled` since plugins are
          // not cross-compiled deps in the dependencies.conf sense.
          Cross.Disabled ->
            Utils.findLatestVersion(organization, name, "sbt-plugin", CrossVersion.disabled)(_.isStableVersion)

        case "compiler-plugin" if isCross =>
          val full   = Utils.findLatestVersion(organization, name, configuration, CrossVersion.full)(_.isStableVersion)
          val binary =
            Utils.findLatestVersion(organization, name, configuration, CrossVersion.binary)(_.isStableVersion)

          (full, binary) match {
            case (Some(f), Some(b)) if Ordering[Numeric].gteq(f, b) => Cross.Full   -> full
            case (Some(_), Some(_))                                 => Cross.Binary -> binary
            case (Some(_), None)                                    => Cross.Full   -> full
            case (None, _)                                          => Cross.Binary -> binary
          }

        case _ =>
          val regular: Cross = if (isCross) Cross.Binary else Cross.Disabled
          Utils.findLatestVersion(organization, name, configuration, regular.toSbt)(_.isStableVersion) match {
            case found @ Some(_) => regular -> found
            case None            =>
              Cross.Disabled ->
                Utils.findLatestVersion(organization, name, "sbt-plugin", CrossVersion.disabled)(_.isStableVersion)
          }
      }

      version
        .map(v => Dependency(organization, name, v, configuration, crossVersion = resolvedCrossVersion))
        .getOrElse(Utils.fail(s"Could not resolve $organization:$name"))
    }

    /** Parses a dependency line, resolving the latest stable version when no version is specified.
      *
      * Delegates to [[parseOrFail]] for lines that include a version. For lines without a version (e.g. `org::name` or
      * `org::name:sbt-plugin`), resolves the latest stable version via the implicit
      * [[com.alejandrohdezma.sbt.dependencies.finders.VersionFinder]] and carries the configuration token through (so
      * `install org::name:sbt-plugin` finds the right artifact shape).
      *
      * Disambiguating `org::name:sbt-plugin` (no version, has config) from `org::name:1.0` (has version, no config) is
      * done by checking whether the captured token after the artifact name parses as a numeric or variable version.
      */
    def parseIncludingMissingVersion(line: String)(implicit
        finders: Finders,
        logger: Logger
    ): Dependency =
      line match {
        case Dependency.dependencyRegex(org, sep, name, null, _) =>
          Dependency.withLatestStableVersion(org, name, isCross = sep === "::")

        case Dependency.dependencyRegex(org, sep, name, possibleConfig, null)
            if !Dependency.looksLikeVersion(possibleConfig) =>
          Dependency.withLatestStableVersion(org, name, isCross = sep === "::", configuration = possibleConfig)

        case other =>
          Dependency.parseOrFail(other)
      }

    /** Like [[Dependency.parse]], but logs and throws on invalid lines (the SBT command behavior). */
    def parseOrFail(line: String)(implicit logger: Logger): Dependency =
      Dependency.parse(line).fold(Utils.fail(_), identity)

    /** Like [[Dependency.validateBomRestrictions]], but logs and throws on invalid combinations. */
    def validateBomRestrictionsOrFail(dependency: Dependency)(implicit logger: Logger): Unit =
      Dependency.validateBomRestrictions(dependency).fold(Utils.fail(_), identity)

  }

}
