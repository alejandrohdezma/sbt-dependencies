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

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/** Navigates between a project definition in `build.sbt` and its dependency group in `project/dependencies.conf` (both
  * directions, via Go to Declaration / Cmd+Click) — the IntelliJ counterpart of the VSCode extension's CodeLens
  * navigation.
  */
final class SbtDependenciesGotoDeclarationHandler extends GotoDeclarationHandler {

  /** The opposite side's element for the name under the caret, or null when the caret is on neither a group name nor a
    * project definition.
    */
  override def getGotoDeclarationTargets(sourceElement: PsiElement, offset: Int, editor: Editor): Array[PsiElement] =
    Option(sourceElement)
      .flatMap(element => Option(element.getContainingFile))
      .flatMap { file =>
        Option(file.getVirtualFile).flatMap { virtualFile =>
          virtualFile.getName match {
            case "dependencies.conf" => groupToProject(file.getText, offset, virtualFile, sourceElement)
            case "build.sbt"         => projectToGroup(file.getText, offset, virtualFile, sourceElement)
            case _                   => None
          }
        }
      }
      .map(Array(_))
      .orNull

  private def groupToProject(
      text: String,
      offset: Int,
      virtualFile: VirtualFile,
      sourceElement: PsiElement
  ): Option[PsiElement] =
    for {
      group <- DependenciesDocument
                 .parse(text)
                 .groups
                 .find(group => group.nameSpan.start <= offset && offset < group.nameSpan.end)
      buildSbt <- Option(virtualFile.getParent)
                    .flatMap(dir => Option(dir.getParent))
                    .flatMap(root => Option(root.findChild("build.sbt")))
      buildSbtPsi      <- Option(PsiManager.getInstance(sourceElement.getProject).findFile(buildSbt))
      definitionOffset <- SbtDependenciesGotoDeclarationHandler.projectDefinitionOffset(buildSbtPsi.getText, group.name)
      target           <- Option(buildSbtPsi.findElementAt(definitionOffset))
    } yield target

  private def projectToGroup(
      text: String,
      offset: Int,
      virtualFile: VirtualFile,
      sourceElement: PsiElement
  ): Option[PsiElement] =
    for {
      name <- SbtDependenciesGotoDeclarationHandler.projectNameAt(text, offset)
      conf <- Option(virtualFile.getParent)
                .flatMap(root => Option(root.findChild("project")))
                .flatMap(project => Option(project.findChild("dependencies.conf")))
      confPsi <- Option(PsiManager.getInstance(sourceElement.getProject).findFile(conf))
      group   <- DependenciesDocument.parse(confPsi.getText).groups.find(_.name == name)
      target  <- Option(confPsi.findElementAt(group.nameSpan.start))
    } yield target

}

object SbtDependenciesGotoDeclarationHandler {

  private val projectPattern = """^\s*lazy\s+val\s+(?:`([^`]+)`|(\w+))\s*=""".r

  /** The name of the project defined at `offset`'s line in a `build.sbt`, when the offset sits on the name itself. */
  def projectNameAt(text: String, offset: Int): Option[String] = {
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1

    val lineEnd = text.indexOf('\n', offset) match {
      case -1    => text.length
      case index => index
    }

    projectPattern.findFirstMatchIn(text.substring(lineStart, lineEnd)).flatMap { matched =>
      val group = if (matched.group(1) != null) 1 else 2

      val start = lineStart + matched.start(group)

      Option.when(start <= offset && offset < lineStart + matched.end(group))(matched.group(group))
    }
  }

  /** The offset of `lazy val <name>` in a `build.sbt`, pointing at the name itself. */
  def projectDefinitionOffset(text: String, name: String): Option[Int] = {
    val lineStarts = text.split("\n", -1).scanLeft(0)((offset, line) => offset + line.length + 1)

    text.linesIterator.zipWithIndex.flatMap { case (line, index) =>
      projectPattern.findFirstMatchIn(line).flatMap { matched =>
        val group = if (matched.group(1) != null) 1 else 2

        Option.when(matched.group(group) == name)(lineStarts(index) + matched.start(group))
      }
    }
      .nextOption()
  }

}
