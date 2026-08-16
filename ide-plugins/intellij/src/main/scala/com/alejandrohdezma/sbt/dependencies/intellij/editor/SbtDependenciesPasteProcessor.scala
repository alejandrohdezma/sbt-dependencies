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

package com.alejandrohdezma.sbt.dependencies.intellij.editor

import com.alejandrohdezma.sbt.dependencies.intellij.lang.SbtDependenciesFile
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Converts SBT-style dependency strings (e.g. copied from mvnrepository.com or a `build.sbt`) into the canonical
  * format when pasted into a `dependencies.conf` file: `"org" %% "artifact" % "1.0.0" % Test` becomes
  * `"org::artifact:1.0.0:test"`. Pasted text without any SBT dependency is left untouched.
  */
final class SbtDependenciesPasteProcessor extends CopyPastePreProcessor {

  /** Copies are never transformed. */
  override def preprocessOnCopy(file: PsiFile, startOffsets: Array[Int], endOffsets: Array[Int], text: String): String =
    null

  /** The pasted text with every SBT-style dependency converted, or the original text when none is found or the target
    * is not a `dependencies.conf` file.
    */
  override def preprocessOnPaste(
      project: Project,
      file: PsiFile,
      editor: Editor,
      text: String,
      rawText: RawText
  ): String =
    file match {
      case _: SbtDependenciesFile => SbtDependenciesPasteProcessor.convertPaste(text).getOrElse(text)
      case _                      => text
    }

}

object SbtDependenciesPasteProcessor {

  private val sbtDependencyPattern =
    ("""^\s*(?:(libraryDependencies\s*\+[+=]|addSbtPlugin\s*\()\s*)?"([^"]+)"\s*(%{1,2})\s*"([^"]+)"\s*%\s*""" +
      """(?:"([^"]+)"|(\w+))(?:\s*%\s*(?:"([^"]+)"|(\w+)))?\s*\)?\s*,?\s*$""").r

  /** Converts every SBT-style dependency line in `text` to the canonical quoted format, skipping blank and comment
    * lines. `None` when the text contains no SBT dependency at all, so the paste proceeds unchanged.
    */
  def convertPaste(text: String): Option[String] = {
    val converted = text
      .split("\r?\n")
      .toList
      .filterNot(line => line.trim.isEmpty || line.trim.startsWith("//") || line.trim.startsWith("#"))
      .flatMap(convertSbtDependency)

    if (converted.isEmpty) None else Some(converted.map(dep => s""""$dep"""").mkString("\n"))
  }

  /** Converts a single SBT-style dependency line (`libraryDependencies +=`, `addSbtPlugin(...)` or a bare
    * `"org" %% "artifact" % "1.0.0"`) to the canonical format. Artifacts with the `_2.12_1.0` suffix and `addSbtPlugin`
    * lines become `:sbt-plugin` dependencies; unquoted versions become `{{variable}}` references and unquoted
    * configurations (`Test`) are lowercased.
    */
  def convertSbtDependency(line: String): Option[String] = line match {
    case sbtDependencyPattern(prefix, org, separator, rawArtifact, version, variable, config, configName) =>
      val sbtPluginSuffix = "_2.12_1.0"

      val artifact =
        if (rawArtifact.endsWith(sbtPluginSuffix)) rawArtifact.dropRight(sbtPluginSuffix.length) else rawArtifact

      val configuration =
        if (rawArtifact.endsWith(sbtPluginSuffix) || Option(prefix).exists(_.startsWith("addSbtPlugin")))
          Some("sbt-plugin")
        else Option(config).orElse(Option(configName).map(_.toLowerCase))

      val resolvedVersion = Option(version).getOrElse(s"{{$variable}}")

      val resolvedSeparator = if (separator == "%%") "::" else ":"

      Some(s"$org$resolvedSeparator$artifact:$resolvedVersion${configuration.fold("")(config => s":$config")}")
    case _ => None
  }

}
