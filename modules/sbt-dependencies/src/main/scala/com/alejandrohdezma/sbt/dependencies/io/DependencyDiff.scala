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

import java.io.File

import scala.collection.JavaConverters._

import sbt.IO
import sbt.librarymanagement.ModuleID

import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.alejandrohdezma.sbt.dependencies.model.Eq._
import com.alejandrohdezma.sbt.dependencies.model.Group
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValueFactory

/** Utilities for snapshotting resolved dependencies and computing diffs. */
object DependencyDiff {

  /** A resolved dependency with organization, artifact name, and revision. */
  final case class ResolvedDep(organization: String, name: String, revision: String)

  object ResolvedDep {

    def from(dependency: Dependency): ResolvedDep =
      ResolvedDep(dependency.organization, dependency.name, dependency.version.toVersionString)

    def fromModuleID(m: ModuleID): ResolvedDep =
      ResolvedDep(m.organization, m.name, m.revision)

  }

  /** A dependency whose version changed between snapshots. */
  final case class UpdatedDep(organization: String, name: String, from: String, to: String)

  /** Per-project diff of resolved dependencies. */
  final case class ProjectDiff(
      updated: List[UpdatedDep],
      added: List[ResolvedDep],
      removed: List[ResolvedDep]
  ) {

    /** Returns true if the diff is empty, i.e. no dependencies were added, removed, or updated. */
    def isEmpty: Boolean = updated.isEmpty && added.isEmpty && removed.isEmpty

  }

  /** Writes a snapshot of resolved dependencies to a HOCON file. */
  def writeSnapshot(file: File, snapshot: Map[Group, Set[ResolvedDep]]): Unit = {
    val rootMap = snapshot.toList
      .sortBy(_._1)
      .map { case (group, deps) =>
        val depsList = deps.toList
          .sortBy(d => (d.organization, d.name, d.revision))
          .map { dep =>
            Map("organization" -> dep.organization, "name" -> dep.name, "revision" -> dep.revision).asJava
          }
          .asJava

        group.name -> ConfigValueFactory.fromAnyRef(depsList)
      }
      .toMap
      .asJava

    val config        = ConfigFactory.parseMap(rootMap)
    val renderOptions = ConfigRenderOptions.defaults().setOriginComments(false).setJson(false)

    IO.write(file, config.root().render(renderOptions).trim + "\n")
  }

  /** Reads a dependency snapshot from a HOCON file previously written by `writeSnapshot`. */
  def readSnapshot(file: File): Map[Group, Set[ResolvedDep]] =
    if (!file.exists()) Map.empty
    else {
      val config = ConfigFactory.parseFile(file)

      def readGroup(group: Group): Set[ResolvedDep] =
        config
          .getConfigList(group.name)
          .asScala
          .map { entry =>
            ResolvedDep(entry.getString("organization"), entry.getString("name"), entry.getString("revision"))
          }
          .toSet

      val groups = config.root().keySet().asScala.map(Group(_))

      groups.map(group => group -> readGroup(group)).toMap
    }

  /** Computes the diff between two dependency snapshots.
    *
    * Keys on `(organization, artifact name)`, ignoring configurations. Returns only groups with non-empty diffs.
    */
  def compute(
      before: Map[Group, Set[ResolvedDep]],
      after: Map[Group, Set[ResolvedDep]]
  ): Map[Group, ProjectDiff] = {
    val allProjects = (before.keySet ++ after.keySet).toList.sorted

    allProjects
      .collect(Function.unlift { project =>
        val beforeDeps = before.getOrElse(project, Set.empty).map(d => (d.organization, d.name) -> d).toMap
        val afterDeps  = after.getOrElse(project, Set.empty).map(d => (d.organization, d.name) -> d).toMap

        val allKeys = (beforeDeps.keySet ++ afterDeps.keySet).toList.sorted

        val updated = allKeys.collect {
          case key
              if beforeDeps.contains(key) && afterDeps.contains(key) &&
                (beforeDeps(key).revision !== afterDeps(key).revision) =>
            UpdatedDep(key._1, key._2, beforeDeps(key).revision, afterDeps(key).revision)
        }

        val added = allKeys.collect {
          case key if !beforeDeps.contains(key) && afterDeps.contains(key) => afterDeps(key)
        }

        val removed = allKeys.collect {
          case key if beforeDeps.contains(key) && !afterDeps.contains(key) => beforeDeps(key)
        }

        val diff = ProjectDiff(updated, added, removed)

        if (diff.isEmpty) None else Some(project -> diff)
      })
      .toMap
  }

  /** Reads a dependency diff from a HOCON file previously written by `toHocon`. */
  def readDiff(file: File): Map[Group, ProjectDiff] =
    if (!file.exists()) Map.empty
    else {
      val config = ConfigFactory.parseFile(file)
      val groups = config.root().keySet().asScala.map(Group(_))

      groups.map { group =>
        val groupConfig = config.getConfig(group.name)

        val updated = groupConfig.getConfigList("updated").asScala.toList.map { entry =>
          UpdatedDep(
            entry.getString("organization"),
            entry.getString("name"),
            entry.getString("from"),
            entry.getString("to")
          )
        }

        val added = groupConfig.getConfigList("added").asScala.toList.map { entry =>
          ResolvedDep(entry.getString("organization"), entry.getString("name"), entry.getString("version"))
        }

        val removed = groupConfig.getConfigList("removed").asScala.toList.map { entry =>
          ResolvedDep(entry.getString("organization"), entry.getString("name"), entry.getString("version"))
        }

        group -> ProjectDiff(updated, added, removed)
      }.toMap
    }

  /** Renders a diff map as HOCON, parseable by `ConfigFactory.parseString`. */
  def toHocon(diffs: Map[Group, ProjectDiff]): String = {
    val rootMap = diffs.toList
      .sortBy(_._1)
      .map { case (group, diff) =>
        val updatedList = diff.updated.map { u =>
          Map("organization" -> u.organization, "name" -> u.name, "from" -> u.from, "to" -> u.to).asJava
        }.asJava

        val addedList = diff.added.map { a =>
          Map("organization" -> a.organization, "name" -> a.name, "version" -> a.revision).asJava
        }.asJava

        val removedList = diff.removed.map { r =>
          Map("organization" -> r.organization, "name" -> r.name, "version" -> r.revision).asJava
        }.asJava

        val groupMap = Map(
          "updated" -> ConfigValueFactory.fromAnyRef(updatedList),
          "added"   -> ConfigValueFactory.fromAnyRef(addedList),
          "removed" -> ConfigValueFactory.fromAnyRef(removedList)
        ).asJava

        group.name -> ConfigValueFactory.fromMap(groupMap)
      }
      .toMap
      .asJava

    val config = ConfigFactory.parseMap(rootMap)

    val renderOptions = ConfigRenderOptions.defaults().setOriginComments(false).setJson(false)

    config.root().render(renderOptions).trim + "\n"
  }

}
