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

  /** Retrieves `coords`' pom file through the fetcher and parses it. */
  private def load(coords: Coords)(implicit fetcher: ModuleFetcher, log: Logger): Pom =
    fetcher
      .fetch(coords.toModuleID)
      .collectFirst {
        case (artifact, file) if artifact.`type` === "pom" || file.getName.endsWith(".pom") =>
          parse(coords, file)
      }
      .getOrElse(sys.error(s"Resolution of $coords returned no pom file"))

  /** The pom for `coords`, loaded on first use and served from a JVM-wide cache afterwards: released poms are
    * immutable, so a parsed coordinate can be reused across BOMs, projects and `BomReader.read` calls.
    */
  def fetch(coords: Coords)(implicit fetcher: ModuleFetcher, log: Logger): Pom =
    cache.computeIfAbsent(coords, load)

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

}
