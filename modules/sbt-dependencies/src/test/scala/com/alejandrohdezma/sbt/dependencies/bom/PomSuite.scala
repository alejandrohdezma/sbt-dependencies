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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.nowarn

import sbt._
import sbt.util.Logger

import com.alejandrohdezma.sbt.dependencies.TestLogger

@nowarn("msg=detected an interpolated expression")
class PomSuite extends munit.FunSuite {

  implicit val logger: Logger = TestLogger()

  test("Pom.effectiveProperties merges own properties over inherited ones") {
    val pom = Pom(Coords("com.example", "library", "1.0.0"), None, Map("a" -> "own", "b" -> "own"), Nil)

    val effective = pom.effectiveProperties(Map("a" -> "inherited", "c" -> "inherited"))

    val expected = Map(
      "a"                  -> "own",
      "b"                  -> "own",
      "c"                  -> "inherited",
      "project.version"    -> "1.0.0",
      "project.groupId"    -> "com.example",
      "project.artifactId" -> "library"
    )

    assertEquals(effective, expected)
  }

  test("Pom.effectiveProperties adds Maven's project.* built-ins, overriding user-defined ones") {
    val pom = Pom(Coords("com.example", "library", "1.0.0"), None, Map("project.version" -> "user"), Nil)

    val effective = pom.effectiveProperties(Map.empty)

    val expected = Map(
      "project.version"    -> "1.0.0",
      "project.groupId"    -> "com.example",
      "project.artifactId" -> "library"
    )

    assertEquals(effective, expected)
  }

  test("Pom.Resolved.entries expands entry placeholders against the effective properties") {
    val pom = Pom(
      coords = Coords("com.example", "bom", "1.0.0"),
      parent = None,
      properties = Map.empty,
      entries = List(
        Entry(Coords("io.netty", "netty-bom", "${netty.version}"), isImport = true),
        Entry(Coords("com.example", "library", "1.2.3"), isImport = false)
      )
    )

    val resolved = Pom.Resolved(pom, Map("netty.version" -> "4.1.100.Final"), 0)

    val expected = List(
      Entry(Coords("io.netty", "netty-bom", "4.1.100.Final"), isImport = true),
      Entry(Coords("com.example", "library", "1.2.3"), isImport = false)
    )

    assertEquals(resolved.entries.toList, expected)
  }

  test("Pom.Resolved.entries drops entries whose placeholders can't be resolved") {
    val pom = Pom(
      coords = Coords("com.example", "bom", "1.0.0"),
      parent = None,
      properties = Map.empty,
      entries = List(
        Entry(Coords("com.example", "library", "${undefined}"), isImport = false),
        Entry(Coords("com.example", "other", "1.2.3"), isImport = false)
      )
    )

    val resolved = Pom.Resolved(pom, Map.empty, 0)

    assertEquals(resolved.entries.toList, List(Entry(Coords("com.example", "other", "1.2.3"), isImport = false)))
  }

  withPomFile {
    """<?xml version="1.0" encoding="UTF-8"?>
      |<project>
      |  <parent>
      |    <groupId>com.fasterxml.jackson</groupId>
      |    <artifactId>jackson-parent</artifactId>
      |    <version>2.17</version>
      |  </parent>
      |  <groupId>com.fasterxml.jackson</groupId>
      |  <artifactId>jackson-bom</artifactId>
      |  <version>2.17.1</version>
      |  <properties>
      |    <jackson.version.core>2.17.1</jackson.version.core>
      |  </properties>
      |  <dependencyManagement>
      |    <dependencies>
      |      <dependency>
      |        <groupId>com.fasterxml.jackson.core</groupId>
      |        <artifactId>jackson-databind</artifactId>
      |        <version>${jackson.version.core}</version>
      |      </dependency>
      |      <dependency>
      |        <groupId>io.netty</groupId>
      |        <artifactId>netty-bom</artifactId>
      |        <version>4.1.100.Final</version>
      |        <type>pom</type>
      |        <scope>import</scope>
      |      </dependency>
      |    </dependencies>
      |  </dependencyManagement>
      |</project>""".stripMargin
  }.test("Pom.parse reads parent, version, properties and managed dependencies") { file =>
    val pom = Pom.parse(Coords("com.fasterxml.jackson", "jackson-bom", "2.17.1"), file)

    val expected = Pom(
      coords = Coords("com.fasterxml.jackson", "jackson-bom", "2.17.1"),
      parent = Some(Coords("com.fasterxml.jackson", "jackson-parent", "2.17")),
      properties = Map("jackson.version.core" -> "2.17.1"),
      entries = List(
        Entry(Coords("com.fasterxml.jackson.core", "jackson-databind", "${jackson.version.core}"), isImport = false),
        Entry(Coords("io.netty", "netty-bom", "4.1.100.Final"), isImport = true)
      )
    )

    assertEquals(pom, expected)
  }

  withPomFile {
    """<?xml version="1.0" encoding="UTF-8"?>
      |<project>
      |  <parent>
      |    <groupId>com.example</groupId>
      |    <artifactId>parent</artifactId>
      |    <version>3.0.0</version>
      |  </parent>
      |  <groupId>com.example</groupId>
      |  <artifactId>library</artifactId>
      |</project>""".stripMargin
  }.test("Pom.parse falls back to the parent's version when the pom declares none") { file =>
    val pom = Pom.parse(Coords("com.example", "library", "requested"), file)

    val expected = Pom(
      coords = Coords("com.example", "library", "3.0.0"),
      parent = Some(Coords("com.example", "parent", "3.0.0")),
      properties = Map.empty,
      entries = Nil
    )

    assertEquals(pom, expected)
  }

  withPomFile {
    """<?xml version="1.0" encoding="UTF-8"?>
      |<project>
      |  <groupId>com.example</groupId>
      |  <artifactId>library</artifactId>
      |</project>""".stripMargin
  }.test("Pom.parse falls back to the requested version without a version or a parent") { file =>
    val pom = Pom.parse(Coords("com.example", "library", "9.9.9"), file)

    val expected = Pom(
      coords = Coords("com.example", "library", "9.9.9"),
      parent = None,
      properties = Map.empty,
      entries = Nil
    )

    assertEquals(pom, expected)
  }

  test("Pom.fetch loads each coordinate only once") {
    val loads = new AtomicInteger(0)

    implicit val fetcher: ModuleFetcher = module => {
      loads.incrementAndGet()

      pomArtifact(module, parent = None)
    }

    val coords = Coords("com.example.cache", "library", UUID.randomUUID().toString)

    val first  = Pom.fetch(coords)
    val second = Pom.fetch(coords)

    assertEquals(first, second)
    assertEquals(loads.get(), 1)
  }

  test("Pom.fetch picks the pom artifact among the module's artifacts") {
    implicit val fetcher: ModuleFetcher = module => {
      val jar = Files.createTempFile(module.name, ".jar").toFile

      (Artifact(module.name, "jar", "jar"), jar) +: pomArtifact(module, parent = None)
    }

    val pom = Pom.fetch(Coords("com.example.artifacts", "library", "1.0.0"))

    assertEquals(pom.coords, Coords("com.example.artifacts", "library", "1.0.0"))
  }

  test("Pom.fetch fails when the module has no pom artifact") {
    implicit val fetcher: ModuleFetcher = _ => Vector.empty

    intercept[RuntimeException](Pom.fetch(Coords("com.example.nopom", "library", "1.0.0")))
  }

  test("Pom.ancestry returns the pom and its parents root-first") {
    val parents = Map("child" -> "middle", "middle" -> "root")

    implicit val fetcher: ModuleFetcher = module => pomArtifact(module, parents.get(module.name))

    val pom = Pom.fetch(Coords("com.example.ancestry", "child", "1.0.0"))

    assertEquals(pom.ancestry.map(_.coords.artifact), List("root", "middle", "child"))
  }

  test("Pom.ancestry fails when the parent chain forms a cycle") {
    val parents = Map("ouroboros-a" -> "ouroboros-b", "ouroboros-b" -> "ouroboros-a")

    implicit val fetcher: ModuleFetcher = module => pomArtifact(module, parents.get(module.name))

    val pom = Pom.fetch(Coords("com.example.cycle", "ouroboros-a", "1.0.0"))

    intercept[RuntimeException](pom.ancestry)
  }

  test("Pom.resolve inherits properties root-first and assigns priorities by distance") {
    implicit val fetcher: ModuleFetcher = module => {
      val (parentXml, properties) = module.name match {
        case "kid" =>
          val parent =
            "<parent><groupId>com.example.resolve</groupId><artifactId>dad</artifactId><version>1.0.0</version></parent>"

          (parent, "<a>kid</a>")
        case _ =>
          ("", "<a>dad</a><b>dad</b>")
      }

      val file = Files.createTempFile(module.name, ".pom").toFile

      IO.write(
        file,
        s"""<?xml version="1.0" encoding="UTF-8"?>
           |<project>
           |  $parentXml
           |  <groupId>com.example.resolve</groupId>
           |  <artifactId>${module.name}</artifactId>
           |  <version>1.0.0</version>
           |  <properties>$properties</properties>
           |</project>""".stripMargin
      )

      Vector((Artifact(module.name, "pom", "pom"), file))
    }

    val resolved = Pom.fetch(Coords("com.example.resolve", "kid", "1.0.0")).resolve(3, Map("seed" -> "s"))

    val expected = List(
      (
        "kid",
        3,
        Map(
          "seed"               -> "s",
          "a"                  -> "kid",
          "b"                  -> "dad",
          "project.version"    -> "1.0.0",
          "project.groupId"    -> "com.example.resolve",
          "project.artifactId" -> "kid"
        )
      ),
      (
        "dad",
        4,
        Map(
          "seed"               -> "s",
          "a"                  -> "dad",
          "b"                  -> "dad",
          "project.version"    -> "1.0.0",
          "project.groupId"    -> "com.example.resolve",
          "project.artifactId" -> "dad"
        )
      )
    )

    assertEquals(resolved.map(r => (r.pom.coords.artifact, r.priority, r.properties)), expected)
  }

  def pomArtifact(module: ModuleID, parent: Option[String]): Vector[(Artifact, File)] = {
    val parentXml = parent.fold("") { name =>
      s"<parent><groupId>${module.organization}</groupId><artifactId>$name</artifactId><version>1.0.0</version></parent>"
    }

    val xml =
      s"""<?xml version="1.0" encoding="UTF-8"?>
         |<project>
         |  $parentXml
         |  <groupId>${module.organization}</groupId>
         |  <artifactId>${module.name}</artifactId>
         |  <version>${module.revision}</version>
         |</project>""".stripMargin

    val file = Files.createTempFile(module.name, ".pom").toFile

    IO.write(file, xml)

    Vector((Artifact(module.name, "pom", "pom"), file))
  }

  def withPomFile(content: String): FunFixture[File] = FunFixture[File](
    setup = { _ =>
      val file = Files.createTempFile("bom", ".pom").toFile
      IO.write(file, content)
      file
    },
    teardown = { file =>
      Files.deleteIfExists(file.toPath)
      ()
    }
  )

}
