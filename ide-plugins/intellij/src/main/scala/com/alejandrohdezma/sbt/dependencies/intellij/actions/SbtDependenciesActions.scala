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

package com.alejandrohdezma.sbt.dependencies.intellij.actions

import com.alejandrohdezma.sbt.dependencies.document.DependenciesDocument
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager

/** Runs an `sbtn` task from the IDE, streaming its output to the Run tool window — the IntelliJ counterpart of the
  * VSCode extension's sbt commands.
  */
object SbtDependenciesActions {

  /** Starts `sbtn` with the given arguments at the build root and shows its output. The virtual file system is
    * refreshed when the process ends, so files the task modified (like `dependencies.conf` after an install) reload
    * without waiting for the window to regain focus.
    */
  def run(project: Project, arguments: List[String]): Unit = {
    val commandLine = new GeneralCommandLine(("sbtn" :: arguments) *)
      .withWorkDirectory(root(project))
      .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

    val handler = new OSProcessHandler(commandLine)

    handler.addProcessListener(new ProcessListener {
      override def processTerminated(event: ProcessEvent): Unit =
        ApplicationManager.getApplication.invokeLater(() => VirtualFileManager.getInstance.asyncRefresh(null): Unit)
    })

    new RunContentExecutor(project, handler)
      .withTitle(("sbtn" :: arguments).mkString(" "))
      .run()
  }

  /** The sbt build root: the grandparent of the open `dependencies.conf` when one is selected (it lives under
    * `project/`), the project base path otherwise.
    */
  def root(project: Project): String =
    FileEditorManager
      .getInstance(project)
      .getSelectedFiles
      .find(_.getName == "dependencies.conf")
      .flatMap(file => Option(file.getParent))
      .flatMap(parent => Option(parent.getParent))
      .map(_.getPath)
      .getOrElse(project.getBasePath)

  /** The `sbtn` arguments installing `dependency` into `group`: the reserved groups use their global tasks, and every
    * other group its project-scoped `install`.
    */
  def installArguments(group: String, dependency: String): List[String] = group match {
    case "sbt-build"       => List("installBuildDependencies", dependency)
    case "common-settings" => List("installCommonDependencies", dependency)
    case group             => List(s"$group/install", dependency)
  }

  /** The group names of the selected `dependencies.conf`, or of `project/dependencies.conf` under the build root. */
  def groups(project: Project): List[String] = {
    val text = FileEditorManager
      .getInstance(project)
      .getSelectedFiles
      .find(_.getName == "dependencies.conf")
      .map(file => new String(file.contentsToByteArray(), file.getCharset))

    text.fold(List.empty[String])(text => DependenciesDocument.parse(text).groups.map(_.name))
  }

}

/** Tools-menu action running `sbtn updateAllDependencies`. */
final class UpdateAllDependenciesAction extends AnAction {

  /** Enablement is project presence only; runs on background thread. */
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT

  /** Runs the task. */
  override def actionPerformed(event: AnActionEvent): Unit =
    Option(event.getProject).foreach(SbtDependenciesActions.run(_, List("updateAllDependencies")))

}

/** Tools-menu action running `sbtn updateDependencies`. */
final class UpdateDependenciesAction extends AnAction {

  /** Enablement is project presence only; runs on background thread. */
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT

  /** Runs the task. */
  override def actionPerformed(event: AnActionEvent): Unit =
    Option(event.getProject).foreach(SbtDependenciesActions.run(_, List("updateDependencies")))

}

/** Tools-menu action prompting for a group and a dependency string, then running the matching install task. */
final class InstallDependencyAction extends AnAction {

  /** Enablement is project presence only; runs on background thread. */
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT

  /** Prompts and runs the install. */
  override def actionPerformed(event: AnActionEvent): Unit =
    Option(event.getProject).foreach { project =>
      val groups = SbtDependenciesActions.groups(project)

      val group = Option(
        Messages.showEditableChooseDialog(
          "Group to install the dependency in:", "Install Dependency", null, groups.toArray, groups.headOption.orNull,
          null
        )
      ).filter(_.nonEmpty)

      group.foreach { group =>
        val dependency = Option(
          Messages.showInputDialog(
            project,
            "Dependency (organization::artifact:version):",
            "Install Dependency",
            null
          )
        ).map(_.trim).filter(_.nonEmpty)

        dependency.foreach(dependency =>
          SbtDependenciesActions.run(project, SbtDependenciesActions.installArguments(group, dependency))
        )
      }
    }

}
