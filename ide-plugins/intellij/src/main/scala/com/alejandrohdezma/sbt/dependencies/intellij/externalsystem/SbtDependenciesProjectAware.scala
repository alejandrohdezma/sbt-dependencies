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

package com.alejandrohdezma.sbt.dependencies.intellij.externalsystem

import java.util
import java.util.concurrent.CopyOnWriteArrayList

import scala.jdk.CollectionConverters._

import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectAware
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectId
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectListener
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectReloadContext
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemRefreshStatus
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

/** Shows IntelliJ's floating "project needs reload" widget when a `dependencies.conf` file changes, mirroring what the
  * sbt integration does for `build.sbt` — the platform tracks the watched files by content, so undoing the change hides
  * the widget again. Clicking the widget runs a full sbt reimport.
  *
  * The sbt integration's own watched-file set is hardcoded (`build.sbt`, `project/build.properties` and any `.sbt` or
  * `.scala` file under `project`) with no extension point, so this registers a second watcher under the sbt system id
  * keyed on the build's `project` directory to never collide with the sbt integration's own registration.
  */
final class SbtDependenciesProjectAware(project: Project, systemId: ProjectSystemId, root: String)
    extends ExternalSystemProjectAware {

  private val listeners = new CopyOnWriteArrayList[ExternalSystemProjectListener]()

  /** Identifies this watcher within the reload tracker. */
  override val getProjectId: ExternalSystemProjectId = new ExternalSystemProjectId(systemId, s"$root/project")

  /** The `dependencies.conf` locations of this build. */
  override def getSettingsFiles: util.Set[String] = SbtDependenciesProjectAware.settingsFiles(root).asJava

  /** Remembers who to notify about reloads, until `parentDisposable` is disposed. */
  override def subscribe(listener: ExternalSystemProjectListener, parentDisposable: Disposable): Unit = {
    listeners.add(listener): Unit

    Disposer.register(parentDisposable, () => listeners.remove(listener): Unit)
  }

  /** Runs a full sbt reimport, the same one the sbt integration runs for `build.sbt` changes. */
  override def reloadProject(context: ExternalSystemProjectReloadContext): Unit = {
    listeners.forEach(_.onProjectReloadStart())

    val callback = new ExternalProjectRefreshCallback {

      override def onSuccess(externalProject: DataNode[ProjectData]): Unit =
        listeners.forEach(_.onProjectReloadFinish(ExternalSystemRefreshStatus.SUCCESS))

      override def onFailure(errorMessage: String, errorDetails: String): Unit =
        listeners.forEach(_.onProjectReloadFinish(ExternalSystemRefreshStatus.FAILURE))

    }

    ExternalSystemUtil.refreshProjects {
      new ImportSpecBuilder(project, systemId).callback(callback).use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
    }
  }

}

object SbtDependenciesProjectAware {

  /** The exact `dependencies.conf` paths watched for a build root. */
  def settingsFiles(root: String): Set[String] = Set(s"$root/dependencies.conf", s"$root/project/dependencies.conf")

}
