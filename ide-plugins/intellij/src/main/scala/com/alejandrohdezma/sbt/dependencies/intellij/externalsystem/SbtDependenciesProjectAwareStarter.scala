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

import scala.jdk.CollectionConverters._

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/** Registers a [[SbtDependenciesProjectAware]] for every linked sbt build once a project opens, so editing its
  * `dependencies.conf` files prompts for an sbt reload. Does nothing when the sbt integration (the Scala plugin) is not
  * installed or the project has no linked sbt build.
  */
final class SbtDependenciesProjectAwareStarter extends StartupActivity.DumbAware {

  /** Looks up the sbt system among the registered external-system managers, so the plugin never depends on the Scala
    * plugin's classes.
    */
  override def runActivity(project: Project): Unit = {
    val log = Logger.getInstance(classOf[SbtDependenciesProjectAwareStarter])

    for {
      systemId <- ExternalSystemApiUtil.getAllManagers.asScala.map(_.getSystemId).find(_.getId == "SBT").toList
      settings <- ExternalSystemApiUtil.getSettings(project, systemId).getLinkedProjectsSettings.asScala
    } {
      val aware   = new SbtDependenciesProjectAware(project, systemId, settings.getExternalProjectPath)
      val tracker = ExternalSystemProjectTracker.getInstance(project)

      log.info(s"registering dependencies.conf watcher for ${aware.getProjectId.getDebugName}")

      tracker.register(aware)
      tracker.activate(aware.getProjectId)
    }
  }

}
