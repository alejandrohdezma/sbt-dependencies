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

package com.alejandrohdezma.sbt.dependencies.intellij.documentation

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument
import com.alejandrohdezma.sbt.dependencies.intellij.lang.SbtDependenciesFile
import com.alejandrohdezma.sbt.dependencies.model.Dependency
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Shows documentation for the dependency under the caret (quick documentation and mouse hover): organization,
  * artifact, version with its update-policy explanation, configuration and a link to mvnrepository.com — mirroring the
  * VSCode extension's hover.
  */
final class SbtDependenciesDocumentationProvider extends AbstractDocumentationProvider {

  /** Makes hover work without references: any element of a `dependencies.conf` file whose offset yields documentation
    * becomes the documentation target.
    */
  override def getCustomDocumentationElement(
      editor: Editor,
      file: PsiFile,
      contextElement: PsiElement,
      targetOffset: Int
  ): PsiElement =
    file match {
      case _: SbtDependenciesFile
          if SbtDependenciesDocumentationProvider.hoverHtml(file.getText, targetOffset).isDefined =>
        contextElement
      case _ => null
    }

  /** The documentation for the dependency entry at the element's offset, or null when the element is not inside one. */
  override def generateDoc(element: PsiElement, originalElement: PsiElement): String =
    Option(element)
      .filter(_.getContainingFile.isInstanceOf[SbtDependenciesFile])
      .flatMap { element =>
        SbtDependenciesDocumentationProvider.hoverHtml(
          element.getContainingFile.getText,
          element.getTextRange.getStartOffset
        )
      }
      .orNull

}

object SbtDependenciesDocumentationProvider {

  /** The hover HTML for whatever sits at `offset`: the name of a reserved group (`sbt-build`, `common-settings`) or a
    * dependency entry. `None` when the offset is on neither or the entry's dependency string doesn't parse.
    */
  def hoverHtml(text: String, offset: Int): Option[String] = {
    val document = DependenciesDocument.parse(text)

    groupHoverHtml(document, offset).orElse(dependencyHoverHtml(document, offset))
  }

  private def groupHoverHtml(document: DependenciesDocument, offset: Int): Option[String] =
    document.groups
      .find(group => group.nameSpan.start <= offset && offset < group.nameSpan.end)
      .flatMap(group => groupDocs.get(group.name))

  private def dependencyHoverHtml(document: DependenciesDocument, offset: Int): Option[String] =
    document.groups
      .flatMap(_.entries)
      .find(entry => entry.span.start <= offset && offset < entry.span.end)
      .flatMap(_.dependency)
      .map(_.value)
      .collect { case Dependency.dependencyRegex(org, separator, name, version, config) =>
        val header = s"<b>$org</b> <code>$separator</code> <b>$name</b>"

        val versionLine = Option(version) match {
          case Some(version) => s"Version: <code>$version</code> <i>(${explanation(version)})</i>"
          case None          => "Version: <i>resolved to latest</i>"
        }

        val configLine = Option(config).map(config => s"<br/>Configuration: <code>$config</code>").getOrElse("")

        val link =
          s"""<a href="${mvnRepositoryUrl(org, name, Option(config))}">Open on mvnrepository</a>"""

        s"<div class='definition'><pre>$header</pre></div><div class='content'>$versionLine$configLine<p>$link</div>"
      }

  /** The mvnrepository.com URL for a dependency. sbt plugins are published under the `_2.12_1.0` artifact suffix. */
  def mvnRepositoryUrl(organization: String, name: String, config: Option[String]): String = {
    val artifact = if (config.contains("sbt-plugin")) s"${name}_2.12_1.0" else name

    s"https://mvnrepository.com/artifact/$organization/$artifact"
  }

  private val groupDocs: Map[String, String] = Map(
    "common-settings" ->
      ("<div class='definition'><pre><b>common-settings</b> — build-wide defaults</pre></div><div class='content'>" +
        "Declares dependencies, Scala versions, and Java target that apply to every non-meta project. A per-project " +
        "group overrides by <code>(organization, name)</code> for deps, or wholesale for " +
        "<code>scala-version[s]</code> and <code>java-version</code>." +
        "<pre><code>common-settings {\n" +
        "  scala-versions = [\"2.13.16\", \"3.3.7\"]\n" +
        "  java-version   = \"17\"\n" +
        "  dependencies   = [\n" +
        "    \"org.typelevel::cats-core:2.10.0\"\n" +
        "  ]\n" +
        "}</code></pre>" +
        "Use <code>installCommonDependencies</code> / <code>updateCommonDependencies</code> from sbt to manage " +
        "entries." +
        "<p><a href=\"https://github.com/alejandrohdezma/sbt-dependencies#readme\">Learn more</a></div>"),
    "sbt-build" ->
      ("<div class='definition'><pre><b>sbt-build</b> — meta-build dependencies</pre></div><div class='content'>" +
        "Declares dependencies for the build definition itself: sbt plugins (<code>:sbt-plugin</code>) and " +
        "libraries used in <code>build.sbt</code>. Cannot define <code>scala-version[s]</code> or " +
        "<code>java-version</code> — those belong in <code>common-settings</code> (build-wide) or in per-project " +
        "groups." +
        "<pre><code>sbt-build = [\n" +
        "  \"ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin\"\n" +
        "  \"org.scalameta:sbt-scalafmt:2.5.4:sbt-plugin\"\n" +
        "]</code></pre>" +
        "The plugin must be installed in <code>project/project/plugins.sbt</code> (the meta-build) for this group " +
        "to work." +
        "<p>Use <code>installBuildDependencies</code> / <code>updateBuildDependencies</code> from sbt to manage " +
        "entries." +
        "<p><a href=\"https://github.com/alejandrohdezma/sbt-dependencies#readme\">Learn more</a></div>")
  )

  private def explanation(version: String): String =
    if (version == "*") "managed by BOM"
    else if (version.startsWith("{{")) "resolved from variable"
    else if (version.startsWith("=")) "pinned"
    else if (version.startsWith("^")) "update within major"
    else if (version.startsWith("~")) "update within minor"
    else "update to latest"

}
