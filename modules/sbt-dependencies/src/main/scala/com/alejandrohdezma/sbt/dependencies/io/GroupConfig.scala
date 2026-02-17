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

import scala.jdk.CollectionConverters._

import com.typesafe.config.Config
import com.typesafe.config.ConfigValueType

/** Represents the configuration for a group in the dependencies file. */
sealed trait GroupConfig {

  /** The list of dependencies for this group. */
  def dependencies: List[String]

  /** The list of Scala versions for this group. */
  def scalaVersions: List[String] = Nil

  /** Formats a group with its configuration for HOCON output. */
  def format(group: String): String = this match {
    case GroupConfig.Simple(deps) =>
      s"""$group = [\n${deps.map(d => s"""  "$d"""").mkString("\n")}\n]"""

    case GroupConfig.Advanced(deps, versions) =>
      val scalaVersionsSection = versions match {
        case Nil           => ""
        case single :: Nil => s"""  scala-version = "$single"\n"""
        case multiple =>
          s"""  scala-versions = [${multiple.map(v => s""""$v"""").mkString(", ")}]\n"""
      }

      val depsSection =
        if (deps.nonEmpty) s"""  dependencies = [\n${deps.map(d => s"""    "$d"""").mkString("\n")}\n  ]"""
        else "  dependencies = []"

      s"$group {\n$scalaVersionsSection$depsSection\n}"
  }

}

object GroupConfig {

  /** Parses a group from a Config, detecting whether it's simple or advanced format. */
  def parse(config: Config, group: String): Either[String, GroupConfig] =
    config.getValue(group).valueType() match {
      case ConfigValueType.LIST =>
        Right(GroupConfig.Simple(config.getStringList(group).asScala.toList))

      case ConfigValueType.OBJECT =>
        val groupConfig = config.getConfig(group)

        val dependencies =
          if (groupConfig.hasPath("dependencies"))
            groupConfig.getValue("dependencies").valueType() match {
              case ConfigValueType.LIST => Right(groupConfig.getStringList("dependencies").asScala.toList)
              case other                => Left(s"'dependencies' must be a list, got $other")
            }
          else Right(Nil)

        val scalaVersions = (groupConfig.hasPath("scala-versions"), groupConfig.hasPath("scala-version")) match {
          case (true, true) =>
            Left("Only one of 'scala-versions' or 'scala-version' can be present")
          case (true, _) =>
            groupConfig.getValue("scala-versions").valueType() match {
              case ConfigValueType.LIST =>
                val list = groupConfig.getStringList("scala-versions").asScala.toList
                if (list.isEmpty) Left("'scala-versions' cannot be empty")
                else Right(list)
              case other => Left(s"'scala-versions' must be a list, got $other")
            }
          case (false, true) =>
            groupConfig.getValue("scala-version").valueType() match {
              case ConfigValueType.STRING => Right(List(groupConfig.getString("scala-version")))
              case other                  => Left(s"'scala-version' must be a string, got $other")
            }
          case (false, false) => Right(Nil)
        }

        dependencies.flatMap(deps => scalaVersions.map(Advanced(deps, _)))

      case other =>
        Left(s"expected list or object, got $other")
    }

  /** Simple format: just a list of dependencies.
    *
    * HOCON representation:
    * {{{
    * my-project = [
    *   "org::name:version"
    *   "org2::name2:version2"
    * ]
    * }}}
    */
  final case class Simple(dependencies: List[String]) extends GroupConfig

  /** Advanced format: an object with dependencies and potentially other fields.
    *
    * HOCON representation:
    * {{{
    * my-project {
    *   scala-versions = ["2.13.12", "2.12.18"]
    *   dependencies = [
    *     "org::name:version"
    *     "org2::name2:version2"
    *   ]
    * }
    * }}}
    */
  final case class Advanced(dependencies: List[String], override val scalaVersions: List[String] = Nil)
      extends GroupConfig

}
