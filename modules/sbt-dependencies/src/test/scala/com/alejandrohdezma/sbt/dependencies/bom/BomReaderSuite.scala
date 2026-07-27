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

package com.alejandrohdezma.sbt.dependencies.bom

import java.nio.file.Files

import scala.annotation.nowarn

import sbt._
import sbt.util.Level
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.TestLogger

@nowarn("msg=detected an interpolated expression")
class BomReaderSuite extends munit.FunSuite {

  implicit val logger: Logger = TestLogger()

  test("BomReader.read flattens parents and imported BOMs into sorted managed dependencies") {
    implicit val fetcher: ModuleFetcher = module =>
      pomArtifact(module) {
        module.name match {
          case "root-bom" =>
            """<parent><groupId>com.example.flatten</groupId><artifactId>parent-pom</artifactId><version>1.0.0</version></parent>
              |<dependencyManagement><dependencies>
              |  <dependency><groupId>org.zebra</groupId><artifactId>lib-a</artifactId><version>1.0.0</version></dependency>
              |  <dependency><groupId>com.example.flatten</groupId><artifactId>imported-bom</artifactId><version>1.0.0</version><type>pom</type><scope>import</scope></dependency>
              |</dependencies></dependencyManagement>""".stripMargin
          case "parent-pom" =>
            """<dependencyManagement><dependencies>
              |  <dependency><groupId>org.apple</groupId><artifactId>lib-c</artifactId><version>3.0.0</version></dependency>
              |</dependencies></dependencyManagement>""".stripMargin
          case _ =>
            """<dependencyManagement><dependencies>
              |  <dependency><groupId>org.mango</groupId><artifactId>lib-b</artifactId><version>2.0.0</version></dependency>
              |</dependencies></dependencyManagement>""".stripMargin
        }
      }

    val flattened = BomReader.read(ModuleID("com.example.flatten", "root-bom", "1.0.0"), "2.13")

    val expected = List(
      ModuleID("org.apple", "lib-c", "3.0.0"),
      ModuleID("org.mango", "lib-b", "2.0.0"),
      ModuleID("org.zebra", "lib-a", "1.0.0")
    )

    assertEquals(flattened.toList, expected)
  }

  test("BomReader.read keeps the nearest declaration when a module is declared twice") {
    implicit val fetcher: ModuleFetcher = module =>
      pomArtifact(module) {
        module.name match {
          case "root-bom" =>
            """<dependencyManagement><dependencies>
              |  <dependency><groupId>org.example</groupId><artifactId>protobuf</artifactId><version>3.0.0</version></dependency>
              |  <dependency><groupId>com.example.eviction</groupId><artifactId>imported-bom</artifactId><version>1.0.0</version><type>pom</type><scope>import</scope></dependency>
              |</dependencies></dependencyManagement>""".stripMargin
          case _ =>
            """<dependencyManagement><dependencies>
              |  <dependency><groupId>org.example</groupId><artifactId>protobuf</artifactId><version>4.0.0</version></dependency>
              |</dependencies></dependencyManagement>""".stripMargin
        }
      }

    val flattened = BomReader.read(ModuleID("com.example.eviction", "root-bom", "1.0.0"), "2.13")

    assertEquals(flattened.toList, List(ModuleID("org.example", "protobuf", "3.0.0")))
  }

  test("BomReader.read caches results, so evictions are logged once across repeated reads") {
    implicit val logger: TestLogger = TestLogger()

    implicit val fetcher: ModuleFetcher = module =>
      pomArtifact(module) {
        module.name match {
          case "root-bom" =>
            """<dependencyManagement><dependencies>
              |  <dependency><groupId>org.example</groupId><artifactId>protobuf</artifactId><version>3.0.0</version></dependency>
              |  <dependency><groupId>com.example.cachelog</groupId><artifactId>imported-bom</artifactId><version>1.0.0</version><type>pom</type><scope>import</scope></dependency>
              |</dependencies></dependencyManagement>""".stripMargin
          case _ =>
            """<dependencyManagement><dependencies>
              |  <dependency><groupId>org.example</groupId><artifactId>protobuf</artifactId><version>4.0.0</version></dependency>
              |</dependencies></dependencyManagement>""".stripMargin
        }
      }

    val bom = ModuleID("com.example.cachelog", "root-bom", "1.0.0")

    val first  = BomReader.read(bom, "2.13")
    val second = BomReader.read(bom, "2.13")

    assertEquals(first.toList, second.toList)

    assertEquals(
      logger.getLogs(Level.Info).filter(_.contains("ignoring")),
      List("BOM com.example.cachelog:root-bom:1.0.0 pins org.example:protobuf to 3.0.0, ignoring 4.0.0")
    )
  }

  test("BomReader.read suffixes cross-versioned BOMs and seeds scala.compat.version") {
    implicit val fetcher: ModuleFetcher = module => {
      assertEquals(module.name, "scala-bom_2.13")

      pomArtifact(module) {
        """<dependencyManagement><dependencies>
          |  <dependency><groupId>org.example</groupId><artifactId>library_${scala.compat.version}</artifactId><version>1.0.0</version></dependency>
          |</dependencies></dependencyManagement>""".stripMargin
      }
    }

    val bom = ModuleID("com.example.cross", "scala-bom", "1.0.0").cross(CrossVersion.binary)

    val flattened = BomReader.read(bom, "2.13")

    assertEquals(flattened.toList, List(ModuleID("org.example", "library_2.13", "1.0.0")))
  }

  test("BomReader.read visits each imported BOM once even on import cycles") {
    implicit val fetcher: ModuleFetcher = module =>
      pomArtifact(module) {
        val other = module.name match {
          case "bom-a" => "bom-b"
          case _       => "bom-a"
        }

        s"""<dependencyManagement><dependencies>
           |  <dependency><groupId>org.example</groupId><artifactId>${module.name}-lib</artifactId><version>1.0.0</version></dependency>
           |  <dependency><groupId>com.example.imports</groupId><artifactId>$other</artifactId><version>1.0.0</version><type>pom</type><scope>import</scope></dependency>
           |</dependencies></dependencyManagement>""".stripMargin
      }

    val flattened = BomReader.read(ModuleID("com.example.imports", "bom-a", "1.0.0"), "2.13")

    val expected = List(
      ModuleID("org.example", "bom-a-lib", "1.0.0"),
      ModuleID("org.example", "bom-b-lib", "1.0.0")
    )

    assertEquals(flattened.toList, expected)
  }

  def pomArtifact(module: ModuleID)(body: String): Vector[(Artifact, File)] = {
    val file = Files.createTempFile(module.name, ".pom").toFile

    IO.write(
      file,
      s"""<?xml version="1.0" encoding="UTF-8"?>
         |<project>
         |  <groupId>${module.organization}</groupId>
         |  <artifactId>${module.name}</artifactId>
         |  <version>${module.revision}</version>
         |  $body
         |</project>""".stripMargin
    )

    Vector((Artifact(module.name, "pom", "pom"), file))
  }

}
