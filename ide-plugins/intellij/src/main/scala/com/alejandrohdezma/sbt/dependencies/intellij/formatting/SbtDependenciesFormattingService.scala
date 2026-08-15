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

package com.alejandrohdezma.sbt.dependencies.intellij.formatting

import java.util

import scala.util.Try

import com.alejandrohdezma.sbt.dependencies.intellij.lang.SbtDependenciesFile
import com.alejandrohdezma.sbt.dependencies.io.GroupConfig
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncDocumentFormattingService.FormattingTask
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.psi.PsiFile

/** Hooks the SBT plugin's canonical rewrite into the platform's Reformat Code action: groups sorted (`sbt-build`,
  * `common-settings`, then alphabetical), dependencies sorted within each group, canonical indentation and comments
  * dropped — exactly what the `dependenciesFormat` task writes. A global rewrite doesn't fit the block-based formatting
  * model, so the document text is replaced wholesale.
  */
final class SbtDependenciesFormattingService extends AsyncDocumentFormattingService {

  /** No optional features (range formatting, import optimization...): only whole-document formatting. */
  override def getFeatures: util.Set[FormattingService.Feature] =
    util.EnumSet.noneOf(classOf[FormattingService.Feature])

  /** Only handles [[SbtDependenciesFile]] documents, leaving every other file to other formatting services. */
  override def canFormat(file: PsiFile): Boolean = file.isInstanceOf[SbtDependenciesFile]

  /** Display name of the formatter in progress messages. */
  override def getName: String = "sbt-dependencies"

  /** Notification group used to report formatting errors. */
  override def getNotificationGroupId: String = "sbt-dependencies"

  /** A task that replaces the document with its [[SbtDependenciesFormattingService.format]] rewrite, or leaves it
    * untouched when the document doesn't parse.
    */
  override def createFormattingTask(request: AsyncFormattingRequest): FormattingTask = new FormattingTask {

    override def run(): Unit = {
      val text = request.getDocumentText

      val formatted = Try(GroupConfig.parseAll(text)).toOption
        .flatMap(_.toOption)
        .map(groups => GroupConfig.render(groups.map { case (group, config) => group -> config.sorted }) + "\n")
        .getOrElse(text)

      request.onTextReady(formatted)
    }

    override def cancel(): Boolean = true

    override def isRunUnderProgress: Boolean = false

  }

}
