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

package com.alejandrohdezma.sbt.dependencies.intellij.structure

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument
import com.alejandrohdezma.sbt.dependencies.intellij.lang.SbtDependenciesIcons
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TextEditorBasedStructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiFile

/** Structure view (outline) for `dependencies.conf`: groups as containers with their dependency lines as children, both
  * navigable. Contents come from the positioned [[DependenciesDocument]] view of the current text.
  */
final class SbtDependenciesOutlineViewFactory extends PsiStructureViewFactory {

  /** A tree-based builder producing a [[SbtDependenciesOutlineViewModel]] for the file. */
  override def getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder =
    new TreeBasedStructureViewBuilder {
      override def createStructureViewModel(editor: Editor): StructureViewModel =
        new SbtDependenciesOutlineViewModel(editor, psiFile)
    }

}

/** Model backing the structure view; recomputed from the current document text on every update. */
final class SbtDependenciesOutlineViewModel(editor: Editor, file: PsiFile)
    extends TextEditorBasedStructureViewModel(editor, file) {

  /** The root tree element: the file node, whose children are the groups. */
  override def getRoot: StructureViewTreeElement = new SbtDependenciesOutlineViewModel.FileElement(file)

}

object SbtDependenciesOutlineViewModel {

  private def navigate(file: PsiFile, offset: Int, requestFocus: Boolean): Unit =
    Option(file.getVirtualFile).foreach { virtualFile =>
      new OpenFileDescriptor(file.getProject, virtualFile, offset).navigate(requestFocus)
    }

  private def presentation(text: String, icon: javax.swing.Icon): ItemPresentation = new ItemPresentation {
    override def getPresentableText: String                 = text
    override def getIcon(unused: Boolean): javax.swing.Icon = icon
  }

  /** Root node: the file itself, with one child per group. */
  final class FileElement(file: PsiFile) extends StructureViewTreeElement {

    /** The PSI file this node represents. */
    override def getValue: AnyRef = file

    /** The file name with the sbt-dependencies logo. */
    override def getPresentation: ItemPresentation = presentation(file.getName, SbtDependenciesIcons.File)

    /** One [[GroupElement]] per group parsed from the current document text. */
    override def getChildren: Array[TreeElement] =
      DependenciesDocument
        .parse(file.getText)
        .groups
        .map(group => new GroupElement(file, group): TreeElement)
        .toArray

    /** No-op: the root node is not navigable. */
    override def navigate(requestFocus: Boolean): Unit = ()

    /** The root node is not navigable. */
    override def canNavigate: Boolean = false

    /** The root node is not navigable. */
    override def canNavigateToSource: Boolean = false

  }

  /** A group node, navigating to the group's name. */
  final class GroupElement(file: PsiFile, group: DependenciesDocument.Group) extends StructureViewTreeElement {

    /** The parsed group this node represents. */
    override def getValue: AnyRef = group

    /** The group name with the platform module icon. */
    override def getPresentation: ItemPresentation = presentation(group.name, AllIcons.Nodes.Module)

    /** One [[DepElement]] per dependency declared in the group. */
    override def getChildren: Array[TreeElement] =
      group.entries
        .flatMap(_.dependency)
        .filter(_.value.nonEmpty)
        .map(dependency => new DepElement(file, dependency): TreeElement)
        .toArray

    /** Opens the file at the group's name. */
    override def navigate(requestFocus: Boolean): Unit =
      SbtDependenciesOutlineViewModel.navigate(file, group.nameSpan.start, requestFocus)

    /** Group nodes are navigable. */
    override def canNavigate: Boolean = true

    /** Group nodes navigate to their source offset. */
    override def canNavigateToSource: Boolean = true

  }

  /** A dependency leaf, navigating to the dependency line. */
  final class DepElement(file: PsiFile, dependency: DependenciesDocument.Field) extends StructureViewTreeElement {

    /** The dependency field this node represents. */
    override def getValue: AnyRef = dependency

    /** The dependency line with the platform library icon. */
    override def getPresentation: ItemPresentation = presentation(dependency.value, AllIcons.Nodes.PpLib)

    /** Dependencies are leaves: no children. */
    override def getChildren: Array[TreeElement] = Array.empty

    /** Opens the file at the dependency line. */
    override def navigate(requestFocus: Boolean): Unit =
      SbtDependenciesOutlineViewModel.navigate(file, dependency.valueSpan.start, requestFocus)

    /** Dependency nodes are navigable. */
    override def canNavigate: Boolean = true

    /** Dependency nodes navigate to their source offset. */
    override def canNavigateToSource: Boolean = true

  }

}
