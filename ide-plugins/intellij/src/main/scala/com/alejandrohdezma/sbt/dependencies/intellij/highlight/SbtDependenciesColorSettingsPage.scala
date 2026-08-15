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

package com.alejandrohdezma.sbt.dependencies.intellij.highlight

import com.alejandrohdezma.sbt.dependencies.intellij.highlight.SbtDependenciesSyntaxHighlighter._
import com.alejandrohdezma.sbt.dependencies.intellij.lang.SbtDependenciesIcons
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

/** The `Settings > Editor > Color Scheme > sbt-dependencies` page: lets users customize every color used by
  * [[SbtDependenciesSyntaxHighlighter]] over a demo document.
  */
final class SbtDependenciesColorSettingsPage extends ColorSettingsPage {

  /** Name of the page in the Color Scheme settings tree. */
  override def getDisplayName: String = "sbt-dependencies"

  /** The sbt-dependencies logo shown next to the page name. */
  override def getIcon: Icon = SbtDependenciesIcons.File

  /** The highlighter used to render the demo text. */
  override def getHighlighter: SyntaxHighlighter = new SbtDependenciesSyntaxHighlighter

  /** One customizable entry per color key, with dependency parts nested under a `Dependency` node. */
  override def getAttributeDescriptors: Array[AttributesDescriptor] = Array(
    new AttributesDescriptor("Comment", Comment),
    new AttributesDescriptor("Group name", GroupName),
    new AttributesDescriptor("Setting key", SettingKey),
    new AttributesDescriptor("Object key", ObjectKey),
    new AttributesDescriptor("String", StringValue),
    new AttributesDescriptor("Keyword", Keyword),
    new AttributesDescriptor("Braces", Braces),
    new AttributesDescriptor("Brackets", Brackets),
    new AttributesDescriptor("Operator", Operator),
    new AttributesDescriptor("Dependency//Organization", Organization),
    new AttributesDescriptor("Dependency//Artifact", Artifact),
    new AttributesDescriptor("Dependency//Version marker", VersionMarker),
    new AttributesDescriptor("Dependency//Version", VersionValue),
    new AttributesDescriptor("Dependency//BOM version (*)", BomStar),
    new AttributesDescriptor("Dependency//Version variable", Variable),
    new AttributesDescriptor("Dependency//Configuration", Configuration)
  )

  /** No standalone (non-text-attribute) colors are exposed. */
  override def getColorDescriptors: Array[ColorDescriptor] = ColorDescriptor.EMPTY_ARRAY

  /** No extra highlighting tags are used in the demo text. */
  override def getAdditionalHighlightingTagToDescriptorMap: java.util.Map[String, TextAttributesKey] = null

  /** Demo document exercising every color key: simple and advanced groups, all version shapes and annotations. */
  override def getDemoText: String =
    """sbt-build = [
      |  "ch.epfl.scala:sbt-scalafix:0.14.6:sbt-plugin"
      |]
      |
      |common-settings {
      |  java-version = "21"
      |  scala-version = "3.8.4"
      |  dependencies = [
      |    "com.permutive::scala-bom:1.2.0:bom"
      |  ]
      |}
      |
      |// Project group
      |core = [
      |  "org.typelevel::cats-core:*"
      |  "io.circe::circe-core:{{circeVersion}}"
      |  "com.example:legacy:=1.0.0"
      |  "org.scalameta::munit:~1.2.4:test"
      |  { dependency = "com.example::pinned:^2.0.0", note = "Newer versions break the API" }
      |]
      |""".stripMargin

}
