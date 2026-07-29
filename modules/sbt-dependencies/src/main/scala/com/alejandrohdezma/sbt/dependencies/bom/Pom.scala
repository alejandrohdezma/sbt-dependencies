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

import java.util.concurrent.ConcurrentHashMap

import scala.xml.Elem
import scala.xml.XML

import sbt._

import com.alejandrohdezma.sbt.dependencies.model.Eq._

/** The slice of a resolved pom BomReader needs: coordinate, optional `<parent>`, `<properties>`, and its
  * `<dependencyManagement>` entries.
  */
private[bom] case class Pom(
    coords: Coords,
    parent: Option[Coords],
    properties: Map[String, String],
    entries: Seq[Entry]
) {

  /** The parent pom, fetched, when this pom declares one. */
  def parentPom(implicit fetcher: ModuleFetcher, log: Logger): Option[Pom] = parent.map(Pom.fetch)

  /** This pom and its parents, root-first; fails when the parent chain forms a cycle. */
  def ancestry(implicit fetcher: ModuleFetcher, log: Logger): List[Pom] = {
    def chain(current: Pom, acc: List[Pom]): List[Pom] = {
      if (acc.exists(_.coords === current.coords)) sys.error(s"Parents of ${current.coords} form a cycle")

      current.parentPom.map(chain(_, current :: acc)).getOrElse(current :: acc)
    }

    chain(this, Nil)
  }

  /** Resolves this pom and its ancestors, nearest-first: properties are inherited root-first over `inherited` (each
    * pom's own entries win), and each pom's entries carry a priority growing with distance from this pom — itself at
    * `priority`, its parent at `priority + 1`, and so on.
    */
  def resolve(priority: Priority, inherited: Map[String, String])(implicit
      fetcher: ModuleFetcher,
      log: Logger
  ): List[Pom.Resolved] = {
    val rootFirst = ancestry

    rootFirst
      .foldLeft((List.empty[Pom.Resolved], inherited, priority + rootFirst.size - 1)) {
        case ((acc, properties, priority), pom) =>
          val resolved = Pom.Resolved.from(pom, properties, priority)

          (resolved :: acc, resolved.properties, priority - 1)
      }
      ._1
  }

  /** Properties derived from the pom's coordinate. */
  def derivedProperties: Map[String, String] = Map(
    "project.version"    -> coords.version,
    "project.groupId"    -> coords.group,
    "project.artifactId" -> coords.artifact
  )

  /** This pom's properties merged over `inherited` (own entries win), plus Maven's `project.*` built-ins. */
  def effectiveProperties(inherited: Map[String, String]): Map[String, String] =
    inherited ++ properties ++ derivedProperties

}

private[bom] object Pom {

  private val cache = new ConcurrentHashMap[Coords, Pom]()

  /** The pom for `coords`, loaded on first use and served from a JVM-wide cache afterwards: released poms are
    * immutable, so a parsed coordinate can be reused across BOMs, projects and `BomReader.read` calls.
    */
  def fetch(coords: Coords)(implicit fetcher: ModuleFetcher, log: Logger): Pom =
    cache.computeIfAbsent(coords, coords => parse(coords, fetcher.fetch(coords.toModuleID)))

  /** Parses a pom file into the `Pom` slice we need — coordinate, optional parent, properties, and managed
    * dependencies. The version falls back to the parent's when the pom declares none.
    */
  def parse(coords: Coords, file: File)(implicit log: Logger): Pom = {
    val xml = XML.loadFile(file)

    def text(node: scala.xml.NodeSeq): String = node.text.trim

    val parent = (xml \ "parent").headOption.map { p =>
      Coords(text(p \ "groupId"), text(p \ "artifactId"), text(p \ "version"))
    }

    val version =
      Option(text(xml \ "version")).filter(_.nonEmpty).orElse(parent.map(_.version)).getOrElse(coords.version)

    val properties = (xml \ "properties").flatMap(_.child).collect { case e: Elem => e.label -> e.text.trim }.toMap

    val entries = (xml \ "dependencyManagement" \ "dependencies" \ "dependency").map { d =>
      Entry(Coords(text(d \ "groupId"), text(d \ "artifactId"), text(d \ "version")), text(d \ "scope") === "import")
    }

    log.debug(
      s"Read BOM pom $coords: parent=$parent, ${properties.size} properties, ${entries.size} managed dependencies"
    )

    Pom(coords.copy(version = version), parent, properties, entries)
  }

  /** A pom from a parent chain, its effective properties, and the `Priority` its entries carry. */
  case class Resolved(pom: Pom, properties: Map[String, String], priority: Priority) {

    /** The pom's entries with placeholders expanded against the effective properties; entries that can't be resolved
      * are logged and dropped.
      */
    def entries(implicit log: Logger): Seq[Entry] =
      pom.entries.flatMap { entry =>
        entry.resolve(properties).orElse {
          log.warn(s"Failed to resolve ${entry.coords} in ${pom.coords}. Ignoring this element.")

          None
        }
      }

  }

  object Resolved {

    /** `pom` resolved at the given `priority`, with its effective properties computed from the `properties` inherited
      * from its ancestors.
      */
    def from(pom: Pom, properties: Map[String, String], priority: Priority): Resolved = {
      val effective = pom.effectiveProperties(properties)

      Pom.Resolved(pom, effective, priority)
    }

  }

}
