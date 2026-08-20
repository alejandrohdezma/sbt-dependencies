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
import java.util.Comparator

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument.Span
import com.alejandrohdezma.sbt.dependencies.intellij.navigation.SbtDependenciesWebLinks.Link

class SbtDependenciesWebLinksSuite extends munit.FunSuite {

  test("links returns one link per dependency entry with its content span") {
    val text =
      """example = [
        |  "org.typelevel::cats-core:2.10.0"
        |  { dependency = "com.google.guava:guava:33.0.0-jre", note = "pinned" }
        |]
        |""".stripMargin

    val result = SbtDependenciesWebLinks.links(text)

    val cats  = "org.typelevel::cats-core:2.10.0"
    val guava = "com.google.guava:guava:33.0.0-jre"

    val expected = List(
      Link(Span(text.indexOf(cats), text.indexOf(cats) + cats.length), "org.typelevel", "::", "cats-core", None),
      Link(Span(text.indexOf(guava), text.indexOf(guava) + guava.length), "com.google.guava", ":", "guava", None)
    )

    assertEquals(result, expected)
  }

  test("links captures the configuration and skips comments, settings and malformed entries") {
    val text =
      """example {
        |  scala-version = "2.13.16"
        |  dependencies = [
        |    // "org.commented::nope:1.0.0"
        |    "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"
        |    "not a dependency"
        |  ]
        |}
        |""".stripMargin

    val result = SbtDependenciesWebLinks.links(text)

    assertEquals(
      result.map(link => (link.organization, link.name, link.config)),
      List(("ch.epfl.scala", "sbt-scalafix", Some("sbt-plugin")))
    )
  }

  test("extractRepositoryUrl prefers <scm><url>") {
    val pom =
      """<project>
        |  <url>https://example.com/site</url>
        |  <scm>
        |    <connection>scm:git:git@github.com:typelevel/cats.git</connection>
        |    <url>https://github.com/typelevel/cats</url>
        |  </scm>
        |</project>
        |""".stripMargin

    assertEquals(SbtDependenciesWebLinks.extractRepositoryUrl(pom), Some("https://github.com/typelevel/cats"))
  }

  test("extractRepositoryUrl cleans scm:git: prefixes and .git suffixes") {
    val pom = "<scm><url>scm:git:https://github.com/typelevel/cats.git</url></scm>"

    assertEquals(SbtDependenciesWebLinks.extractRepositoryUrl(pom), Some("https://github.com/typelevel/cats"))
  }

  test("extractRepositoryUrl normalizes http to https") {
    val pom = "<scm><url>http://github.com/typelevel/cats</url></scm>"

    assertEquals(SbtDependenciesWebLinks.extractRepositoryUrl(pom), Some("https://github.com/typelevel/cats"))
  }

  test("extractRepositoryUrl rejects non-https scm urls but falls back to a known-host <url>") {
    val pom =
      """<project>
        |  <url>https://github.com/typelevel/cats</url>
        |  <scm><url>git@github.com:typelevel/cats.git</url></scm>
        |</project>
        |""".stripMargin

    assertEquals(SbtDependenciesWebLinks.extractRepositoryUrl(pom), Some("https://github.com/typelevel/cats"))
  }

  test("extractRepositoryUrl ignores a top-level <url> on an unknown host") {
    val pom = "<project><url>https://typelevel.org/cats</url></project>"

    assertEquals(SbtDependenciesWebLinks.extractRepositoryUrl(pom), None)
  }

  cache.test("findMavenBases finds nested maven layouts up to four levels deep") { cache =>
    Files.createDirectories(cache.resolve("https/repo1.maven.org/maven2/org"))
    Files.createDirectories(cache.resolve("https/oss.sonatype.org/content/repositories/releases/org"))

    assertEquals(
      SbtDependenciesWebLinks.findMavenBases(cache.resolve("https/repo1.maven.org"), "org"),
      List(cache.resolve("https/repo1.maven.org/maven2"))
    )

    assertEquals(
      SbtDependenciesWebLinks.findMavenBases(cache.resolve("https/oss.sonatype.org"), "org"),
      List(cache.resolve("https/oss.sonatype.org/content/repositories/releases"))
    )
  }

  cache.test("resolveRepositoryUrl reads the POM of a Scala dependency trying _3 and _2.13 suffixes") { cache =>
    write(
      cache.resolve("https/repo1.maven.org/maven2/org/typelevel/cats-core_2.13/2.10.0/cats-core_2.13-2.10.0.pom"),
      "<scm><url>https://github.com/typelevel/cats</url></scm>"
    )

    val result = SbtDependenciesWebLinks.resolveRepositoryUrl(
      Link(Span(0, 0), "org.typelevel", "::", "cats-core", None),
      cache
    )

    assertEquals(result, Some("https://github.com/typelevel/cats"))
  }

  cache.test("resolveRepositoryUrl uses the _2.12_1.0 suffix for sbt plugins") { cache =>
    write(
      cache.resolve("https/repo1.maven.org/maven2/ch/epfl/scala/sbt-scalafix_2.12_1.0/0.14.5/sbt-scalafix-0.14.5.pom"),
      "<scm><url>https://github.com/scalacenter/sbt-scalafix</url></scm>"
    )

    val result = SbtDependenciesWebLinks.resolveRepositoryUrl(
      Link(Span(0, 0), "ch.epfl.scala", ":", "sbt-scalafix", Some("sbt-plugin")),
      cache
    )

    assertEquals(result, Some("https://github.com/scalacenter/sbt-scalafix"))
  }

  cache.test("resolveRepositoryUrl returns None when no cached POM declares a repository") { cache =>
    write(
      cache.resolve("https/repo1.maven.org/maven2/org/typelevel/cats-core_2.13/2.10.0/cats-core_2.13-2.10.0.pom"),
      "<project><url>https://typelevel.org/cats</url></project>"
    )

    val result = SbtDependenciesWebLinks.resolveRepositoryUrl(
      Link(Span(0, 0), "org.typelevel", "::", "cats-core", None),
      cache
    )

    assertEquals(result, None)
  }

  test("coursierCachePath honors COURSIER_CACHE and falls back per platform") {
    val home = Paths.get("/home/alex")

    assertEquals(
      SbtDependenciesWebLinks.coursierCachePath(Map("COURSIER_CACHE" -> "/custom").get, "Mac OS X", home),
      Paths.get("/custom")
    )

    assertEquals(
      SbtDependenciesWebLinks.coursierCachePath(Map.empty[String, String].get, "Mac OS X", home),
      Paths.get("/home/alex/Library/Caches/Coursier/v1")
    )

    assertEquals(
      SbtDependenciesWebLinks.coursierCachePath(Map.empty[String, String].get, "Linux", home),
      Paths.get("/home/alex/.cache/coursier/v1")
    )

    assertEquals(
      SbtDependenciesWebLinks.coursierCachePath(Map.empty[String, String].get, "Windows 11", home),
      Paths.get("/home/alex/AppData/Local/Coursier/Cache/v1")
    )
  }

  lazy val cache = FunFixture[Path](
    setup = _ => Files.createTempDirectory("coursier-cache"),
    teardown = dir => Files.walk(dir).sorted(Comparator.reverseOrder[Path]()).forEach(Files.delete(_))
  )

  private def write(file: Path, content: String): Unit = {
    Files.createDirectories(file.getParent)
    Files.writeString(file, content): Unit
  }

}
