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

package com.alejandrohdezma.sbt.dependencies

import scala.collection.JavaConverters._

/** Represents the configuration for a group in the dependencies file. */
sealed trait GroupConfig {

  /** The list of dependencies for this group. */
  def dependencies: List[String]

  /** The list of Scala versions for this group. */
  def scalaVersions: List[String] = Nil

  /** Formats a group with its configuration for YAML output. */
  def format(group: String): String = this match {
    case GroupConfig.Simple(deps) =>
      s"$group:\n${deps.map(d => s"  - $d").mkString("\n")}"

    case GroupConfig.Advanced(deps, versions) =>
      val scalaVersionsSection =
        if (versions.nonEmpty) s"  scala-versions:\n${versions.map(v => s"    - $v").mkString("\n")}\n"
        else ""

      val depsSection =
        if (deps.nonEmpty) s"  dependencies:\n${deps.map(d => s"    - $d").mkString("\n")}"
        else "  dependencies: []"

      s"$group:\n$scalaVersionsSection$depsSection"
  }

}

object GroupConfig {

  /** Parses a group value, detecting whether it's simple or advanced format. */
  def parse(value: Object): Either[String, GroupConfig] = value match {
    case list: java.util.List[_] =>
      Right(GroupConfig.Simple(list.asScala.toList.map(_.toString))) // scalafix:ok

    case map: java.util.Map[_, _] =>
      val scalaMap = map.asScala.toMap.map { case (k, v) => k.toString -> v } // scalafix:ok

      val dependencies = scalaMap.get("dependencies") match {
        case Some(list: java.util.List[_]) => Right(list.asScala.toList.map(_.toString)) // scalafix:ok
        case Some(other)                   => Left(s"'dependencies' must be a list, got ${other.getClass.getSimpleName}")
        case None                          => Right(Nil)
      }

      val scalaVersions = scalaMap.get("scala-versions") match {
        case Some(list: java.util.List[_]) if list.isEmpty() => Left("'scala-versions' cannot be empty")
        case Some(list: java.util.List[_])                   => Right(list.asScala.toList.map(_.toString)) // scalafix:ok
        case Some(other)                                     => Left(s"'scala-versions' must be a list, got ${other.getClass.getSimpleName}")
        case None                                            => Right(Nil)
      }

      dependencies.flatMap(dependencies => scalaVersions.map(Advanced(dependencies, _)))

    case other =>
      Left(s"expected list or map, got ${Option(other).map(_.getClass.getSimpleName).getOrElse("null")}")
  }

  /** Simple format: just a list of dependencies.
    *
    * YAML representation:
    * {{{
    * my-project:
    *   - org::name:version
    *   - org2::name2:version2
    * }}}
    */
  final case class Simple(dependencies: List[String]) extends GroupConfig

  /** Advanced format: an object with dependencies and potentially other fields.
    *
    * YAML representation:
    * {{{
    * my-project:
    *   scala-versions:
    *     - 2.13.12
    *     - 2.12.18
    *   dependencies:
    *     - org::name:version
    *     - org2::name2:version2
    * }}}
    */
  final case class Advanced(dependencies: List[String], override val scalaVersions: List[String] = Nil)
      extends GroupConfig

}
