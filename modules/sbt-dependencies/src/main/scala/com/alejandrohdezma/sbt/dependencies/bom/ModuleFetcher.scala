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

package com.alejandrohdezma.sbt.dependencies.bom

import java.util.Properties

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import sbt._

import com.alejandrohdezma.sbt.dependencies.finders.Utils
import coursier.cache.FileCache
import coursier.credentials.DirectCredentials
import coursier.ivy.PropertiesPattern
import coursier.util.Artifact

/** Fetches a module's pom file. This is the seam between the pom model and sbt's dependency resolution, so tests can
  * substitute a stub serving pre-baked pom files.
  */
private[dependencies] trait ModuleFetcher {

  /** The pom file `moduleID` resolves to; fails the build when no configured resolver has it. */
  def fetch(moduleID: ModuleID): File

}

private[dependencies] object ModuleFetcher {

  /** A fetcher that downloads the module's pom as a single file through coursier's cache, trying each resolver's pom
    * URL in order.
    *
    * It deliberately does not resolve the pom as a dependency: resolving even an intransitive, pom-only module makes
    * Ivy's `PomModuleDescriptorParser` build module descriptors for the pom's whole parent/import closure, and for
    * every `packaging=pom` descriptor `PomModuleDescriptorBuilder.addMainArtifact` silently probes the network for a
    * main jar those modules never publish — a cold read of a large BOM tree (google-cloud's `libraries-bom` expands to
    * 200+ poms) used to take ~12 minutes with no output. A raw file download never parses the pom, costs at most one
    * GET per repository tried, and is served from coursier's cache with zero network calls on warm loads.
    *
    * It uses coursier rather than Ivy because sbt 2.1 no longer ships Ivy. Maven resolvers (including `file:` ones) and
    * Ivy-pattern resolvers such as `~/.ivy2/local` are supported; other resolver types are skipped. Changing
    * (`-SNAPSHOT`) revisions are re-checked on every load. Credentials come from the first of sbt's conventional
    * credential files (see [[credentials]]) plus coursier's own credential sources.
    */
  def fromCoursier(resolvers: Seq[Resolver])(implicit log: Logger): ModuleFetcher = {
    val cache = FileCache().addCredentials(credentials *).withTtl(0.seconds)

    implicit val ec: ExecutionContext = cache.ec

    moduleID => {
      val changing = moduleID.revision.endsWith("-SNAPSHOT")

      @tailrec
      def loop(urls: List[String], errors: List[String]): File = urls match {
        case Nil =>
          Utils.fail {
            if (errors.isEmpty) s"Failed to resolve $moduleID: pom not found in any of the configured resolvers"
            else s"Failed to resolve $moduleID:\n${errors.reverse.mkString("\n")}"
          }

        case url :: rest =>
          cache.file(Artifact(url, Map.empty, Map.empty, changing, false, None)).run.unsafeRun() match {
            case Right(file) => file
            case Left(error) => loop(rest, s"  $url: ${error.describe}" :: errors)
          }
      }

      loop(resolvers.toList.flatMap(pomUrls(_, moduleID)), Nil)
    }
  }

  /** Where `resolver` would serve `moduleID`'s pom, if it is a Maven or an Ivy-pattern resolver. */
  private[bom] def pomUrls(resolver: Resolver, moduleID: ModuleID): Seq[String] = resolver match {
    case repository: MavenRepository =>
      val path = s"${moduleID.organization.replace('.', '/')}/${moduleID.name}/${moduleID.revision}"

      Seq(s"${repository.root.stripSuffix("/")}/$path/${moduleID.name}-${moduleID.revision}.pom")

    case repository: PatternsBasedRepository =>
      val organization =
        if (repository.patterns.isMavenCompatible) moduleID.organization.replace('.', '/')
        else moduleID.organization

      val variables = Map(
        "organisation" -> organization,
        "module"       -> moduleID.name,
        "artifact"     -> moduleID.name,
        "revision"     -> moduleID.revision,
        "type"         -> "pom",
        "ext"          -> "pom"
      )

      repository.patterns.artifactPatterns.flatMap {
        PropertiesPattern
          .parse(_)
          .flatMap(_.substituteProperties(sys.props.toMap))
          .flatMap(_.substituteVariables(variables))
          .toOption
          .map(location => if (repository.isInstanceOf[FileRepository]) file(location).toURI.toString else location)
      }

    case _ => Nil
  }

  /** The credentials in the first existing sbt credential file, checking sbt's conventional locations in the order sbt
    * itself consults them, and accepting the same property names sbt's own loader does.
    */
  private def credentials(implicit log: Logger): Seq[DirectCredentials] =
    Seq(
      sys.props.get("sbt.boot.credentials").filter(_.nonEmpty),
      sys.env.get("SBT_CREDENTIALS"),
      Some(s"${sys.props("user.home")}/.ivy2/.credentials"),
      Some(s"${sys.props("user.home")}/.sbt/.credentials")
    ).flatten.map(new File(_)).find(_.exists()).toList.flatMap { file =>
      val properties = new Properties()
      IO.load(properties, file)

      def lookup(keys: String*) = keys.collectFirst {
        case key if properties.containsKey(key) => properties.getProperty(key)
      }

      (
        lookup("host", "hostname"),
        lookup("user", "user.name", "username"),
        lookup("password", "pwd", "pass", "passwd")
      ) match {
        case (Some(host), Some(user), Some(password)) =>
          List(DirectCredentials(host, user, password, lookup("realm")))

        case _ =>
          log.warn(s"Ignoring credentials in $file: it needs a `host`, a `user` and a `password`")
          Nil
      }
    }

}
