import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName

val catsVersion = "2.10.0"

ThisBuild / dependencyVersionVariables += "catsVersion" -> { (oa: OrganizationArtifactName) => oa % catsVersion }

lazy val myproject = project

lazy val app = project.dependsOn(myproject)

@transient lazy val assertDump = taskKey[Unit]("Assert the resolutions dump was written with the expected content")

assertDump := {
  val dump = (ThisBuild / baseDirectory).value / "target" / "sbt-dependencies" / ".sbt-resolutions"

  assert(dump.exists, s"Expected ${dump.getPath} to exist")

  val content = IO.read(dump)

  val expected = List(
    // a BOM declared in common-settings, flattened with its pins
    "\"com.fasterxml.jackson:jackson-bom:2.16.0@2.13\"",
    "\"name\": \"jackson-databind\", \"version\": \"2.16.0\"",
    // a BOM declared in myproject
    "\"org.junit:junit-bom:5.10.0@2.13\"",
    // the `{{catsVersion}}` variable dependency resolved to its version
    "\"name\": \"cats-core\", \"cross\": true, \"variable\": \"catsVersion\", \"version\": \"2.10.0\"",
    // common-settings appears as its own project entry
    "\"common-settings\": {",
    // app inherits myproject's junit-bom through dependsOn, so it lists that BOM key
    "\"app\": {\"scalaBinaryVersions\": [\"2.13\"], \"boms\": [\"com.fasterxml.jackson:jackson-bom:2.16.0@2.13\", \"org.junit:junit-bom:5.10.0@2.13\"]"
  )

  expected.foreach { fragment =>
    assert(content.contains(fragment), s"Expected the dump to contain:\n$fragment\n\ngot:\n$content")
  }
}
