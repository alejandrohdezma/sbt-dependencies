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

package com.alejandrohdezma.sbt.dependencies.intellij.navigation

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument
import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span
import com.alejandrohdezma.sbt.dependencies.intellij.documentation.SbtDependenciesDocumentationProvider
import com.alejandrohdezma.sbt.dependencies.model.Dependency

/** Web links for the dependencies of a `dependencies.conf` document. Each dependency links to its project repository
  * URL when its POM can be found in the local Coursier cache (`<scm><url>` first, then a top-level `<url>` on a known
  * repository host), falling back to mvnrepository.com otherwise.
  */
object SbtDependenciesWebLinks {

  /** A dependency entry's string content span together with its parsed coordinates. */
  final case class Link(span: Span, organization: String, separator: String, name: String, config: Option[String])

  private val knownRepositoryHosts = List("github.com", "gitlab.com", "bitbucket.org", "codeberg.org")

  private val scmUrlRegex = """(?s)<scm>.*?<url>(.*?)</url>.*?</scm>""".r.unanchored

  private val urlRegex = """<url>(.*?)</url>""".r.unanchored

  private val urlCache = new ConcurrentHashMap[String, String]()

  private val mavenBaseCache = new ConcurrentHashMap[(Path, String), List[Path]]()

  /** Every parseable dependency entry in the document, with the span of its string content. */
  def links(text: String): List[Link] =
    DependenciesDocument.parse(text).groups.flatMap(_.entries).flatMap(_.dependency).collect {
      case DependenciesDocument.Field(Dependency.dependencyRegex(org, separator, name, _, config), span) =>
        Link(span, org, separator, name, Option(config))
    }

  /** The URL a dependency links to: its repository URL from the Coursier cache when found, mvnrepository.com otherwise.
    * Results are cached for the IDE session, so the cache scan runs once per dependency.
    */
  def urlFor(link: Link): String =
    urlCache.computeIfAbsent(
      s"${link.organization}:${link.name}:${link.separator}:${link.config.getOrElse("")}",
      _ =>
        resolveRepositoryUrl(link)
          .getOrElse(SbtDependenciesDocumentationProvider.mvnRepositoryUrl(link.organization, link.name, link.config))
    )

  /** The repository URL for a dependency, found by reading its POM files in the local Coursier cache. `None` when no
    * cached POM declares one.
    */
  def resolveRepositoryUrl(link: Link, cache: Path = coursierCachePath()): Option[String] = {
    val candidates = link match {
      case link if link.config.contains("sbt-plugin") => List(s"${link.name}_2.12_1.0")
      case link if link.separator == "::"             => List(s"${link.name}_3", s"${link.name}_2.13")
      case link                                       => List(link.name)
    }

    val orgPath           = link.organization.split('.').toList
    val orgFirstComponent = orgPath.head

    val urls = for {
      hostDir    <- readDir(cache.resolve("https")).iterator
      mavenBase  <- cachedMavenBases(cache.resolve("https").resolve(hostDir), orgFirstComponent)
      candidate  <- candidates
      artifactDir = orgPath.foldLeft(mavenBase)(_.resolve(_)).resolve(candidate)
      version    <- readDir(artifactDir)
      versionDir  = artifactDir.resolve(version)
      pom        <- readDir(versionDir).find(_.endsWith(".pom"))
      content    <- readFile(versionDir.resolve(pom))
      url        <- extractRepositoryUrl(content)
    } yield url

    urls.nextOption()
  }

  /** The repository URL declared in a POM: `<scm><url>` first, then the top-level `<url>` when it points at a known
    * repository host.
    */
  def extractRepositoryUrl(pomXml: String): Option[String] =
    (pomXml match {
      case scmUrlRegex(url) => cleanUrl(url)
      case _                => None
    }).orElse {
      pomXml match {
        case urlRegex(url) if knownRepositoryHosts.exists(url.contains) => cleanUrl(url)
        case _                                                          => None
      }
    }

  /** The Maven layout roots under a Coursier cache host directory: directories containing the organization's first path
    * component, searched breadth-first up to four levels deep. Different repositories nest the layout differently
    * (`repo1.maven.org/maven2`, `oss.sonatype.org/content/repositories/releases`...).
    */
  def findMavenBases(hostDir: Path, orgFirstComponent: String): List[Path] = {
    val results = List.newBuilder[Path]
    val queue   = mutable.Queue((hostDir, 0))

    while (queue.nonEmpty) {
      val (dir, depth) = queue.dequeue()
      val entries      = readDir(dir)

      if (entries.contains(orgFirstComponent)) results += dir
      else if (depth < 4) entries.foreach(entry => queue.enqueue((dir.resolve(entry), depth + 1)))
    }

    results.result()
  }

  /** The Coursier cache directory: the `COURSIER_CACHE` environment variable when set, the platform default otherwise. */
  def coursierCachePath(
      env: String => Option[String] = sys.env.get,
      osName: String = sys.props("os.name"),
      home: Path = Paths.get(sys.props("user.home"))
  ): Path =
    env("COURSIER_CACHE").map(Paths.get(_)).getOrElse {
      if (osName.toLowerCase.contains("mac"))
        home.resolve("Library").resolve("Caches").resolve("Coursier").resolve("v1")
      else if (osName.toLowerCase.contains("win"))
        env("LOCALAPPDATA")
          .map(Paths.get(_))
          .getOrElse(home.resolve("AppData").resolve("Local"))
          .resolve("Coursier")
          .resolve("Cache")
          .resolve("v1")
      else home.resolve(".cache").resolve("coursier").resolve("v1")
    }

  private def cleanUrl(url: String): Option[String] = {
    val cleaned = url.trim
      .replaceAll("""^scm:git:[^/]*(?=https?://)""", "")
      .replaceAll("""^scm:git:""", "")
      .replaceAll("""\.git$""", "")
      .replaceAll("""^http://""", "https://")

    Option.when(cleaned.startsWith("https://"))(cleaned)
  }

  private def cachedMavenBases(hostDir: Path, orgFirstComponent: String): List[Path] =
    mavenBaseCache.computeIfAbsent((hostDir, orgFirstComponent), _ => findMavenBases(hostDir, orgFirstComponent))

  private def readDir(dir: Path): List[String] =
    Try {
      val stream = Files.list(dir)

      try stream.iterator().asScala.map(_.getFileName.toString).toList
      finally stream.close()
    }.getOrElse(List.empty)

  private def readFile(file: Path): Option[String] =
    Try(Files.readString(file)).toOption

}
