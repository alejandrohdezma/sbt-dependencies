lazy val myproject = project

val catsVersion = "2.10.0"

ThisBuild / dependencyVersionVariables := Map(
  "catsVersion" -> { oa => oa % catsVersion }
)

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  val deps = (myproject / libraryDependencies).value
  val catsDep = deps.find(_.name == "cats-core")

  assert(catsDep.isDefined, "cats-core dependency not found")
  assert(catsDep.get.revision == "2.10.0", s"Expected cats-core:2.10.0, got ${catsDep.get.revision}")
}
