lazy val myproject = project

@transient lazy val assertTest = taskKey[Unit]("Assert each Scala version gets only its own coursier entry")

myproject / assertTest := {
  val scalaV   = (myproject / scalaVersion).value
  val versions = (myproject / libraryDependencies).value.filter(_.name == "coursier").map(_.revision)
  val expected = if (scalaV.startsWith("2.12")) "2.1.23" else "2.1.25"

  assert(versions == Seq(expected), s"Expected only coursier:$expected for Scala $scalaV, got $versions")
}

@transient lazy val assertUpdated = taskKey[Unit]("Assert updateDependencies kept and updated both entries")

assertUpdated := {
  val content = IO.read((ThisBuild / baseDirectory).value / "project" / "dependencies.conf")

  assert(content.contains("""{ dependency = "io.get-coursier::coursier:2.1.24", scala-filter = "2.12" }"""), content)
  assert(content.contains("""scala-filter = "3" }"""), content)
  assert(!content.contains("2.1.25"), content)
}
