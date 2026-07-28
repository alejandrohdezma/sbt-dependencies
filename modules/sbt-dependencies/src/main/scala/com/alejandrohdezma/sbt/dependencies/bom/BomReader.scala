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

import scala.annotation.tailrec

import sbt._

import com.alejandrohdezma.sbt.dependencies.PluginCompat
import com.alejandrohdezma.sbt.dependencies.model.Eq._

/** Reads an external Maven BOM and returns its flattened, effective managed dependencies.
  *
  * Implements the subset of Maven model resolution that BOMs need: parent-pom inheritance, recursive
  * `<scope>import</scope>` BOMs, and Maven placeholder interpolation (`project.version` and user-defined properties).
  * Duplicate `(groupId, artifactId)` declarations are resolved nearest-declaration-wins: an entry declared closer to
  * the requested BOM beats one inherited from a parent or a deeper import (so `libraries-bom-protobuf3` keeps protobuf
  * 3.x over the 4.x line its parent imports), with ties going to the first declaration in document order — matching
  * Maven.
  *
  * The algorithm is a port of `BomReader` from HERE's plugin (https://github.com/heremaps/here-sbt-bom, Apache-2.0),
  * reimplemented on scala-xml and sbt's `DependencyResolution` instead of Apache Ivy internals.
  */
object BomReader {

  private val cache = new ConcurrentHashMap[(Coords, String), Seq[ModuleID]]()

  /** Resolves `bom`'s parent chains and `<scope>import</scope>` tree into a flat, de-duplicated list of managed
    * dependencies, sorted by organization then name. A cross-versioned `bom` gets its artifact name suffixed with
    * `scalaBinaryVersion` before resolving. For BOMs on authenticated repositories, call [[loadCredentials]] first.
    *
    * Results are served from a JVM-wide cache keyed by the BOM coordinate and Scala binary version: released poms are
    * immutable (the same assumption `Pom.fetch` makes), so a BOM flattened once is reused across projects — and its
    * eviction messages are logged only on the first (cache-missing) read rather than once per consumer.
    */
  private[dependencies] def read(bom: ModuleID, scalaBinaryVersion: String)(implicit
      log: Logger,
      fetcher: ModuleFetcher
  ): Seq[ModuleID] =
    cache.computeIfAbsent(
      (Coords(bom, scalaBinaryVersion), scalaBinaryVersion),
      { case (coords, scalaVersion) =>
        extract(List((coords, 0)), Map("scala.compat.version" -> scalaVersion))
          .groupBy(_._1.module)
          .toList
          .map { case (_, versions) =>
            val best    = versions.minBy(_._2)._1
            val evicted = versions.map(_._1.version).distinct.filterNot(_ === best.version)

            if (evicted.nonEmpty)
              log.info(s"BOM $coords pins ${best.module} to ${best.version}, ignoring ${evicted.mkString(", ")}")

            best.toModuleID
          }
          .sortBy(m => (m.organization, m.name))
      }
    )

  /** Breadth-first walk over the import tree: plain entries are collected with the priority of the pom that declared
    * them, imported BOMs are enqueued one level further away.
    */
  @tailrec
  private def extract(
      pending: List[(Coords, Priority)],
      properties: Map[String, String],
      visited: Set[Coords] = Set.empty,
      acc: Vector[(Coords, Priority)] = Vector.empty
  )(implicit fetcher: ModuleFetcher, log: Logger): Vector[(Coords, Priority)] = pending match {
    case Nil                        => acc
    case (coords, priority) :: rest =>
      val resolved = Pom.fetch(coords).resolve(priority, properties)

      val (artifacts, imports) =
        resolved.foldLeft((Vector.empty[(Coords, Priority)], List.empty[(Coords, Priority)])) {
          case ((artifacts, imports), pom) =>
            val (importEntries, plain) = pom.entries.partition(_.isImport)

            (
              artifacts ++ plain.map(e => (e.coords, pom.priority)),
              imports ++ importEntries.map(e => (e.coords, pom.priority + 1))
            )
        }

      val newImports = imports.filterNot { case (coords, _) => visited.contains(coords) }

      extract(rest ++ newImports, properties, visited ++ resolved.map(_.pom.coords), acc ++ artifacts)
  }

  /** Registers the first existing credential file into Ivy's global store, checking sbt's conventional locations in the
    * order sbt itself consults them, so `read` can resolve BOMs living on authenticated repositories.
    */
  def loadCredentials(implicit log: Logger): Unit =
    Seq(
      sys.props.get("sbt.boot.credentials").filter(_.nonEmpty),
      sys.env.get("SBT_CREDENTIALS"),
      Some(s"${sys.props("user.home")}/.ivy2/.credentials"),
      Some(s"${sys.props("user.home")}/.sbt/.credentials")
    ).flatten.map(new File(_)).find(_.exists()).foreach(PluginCompat.registerCredentials(_, log))

}
