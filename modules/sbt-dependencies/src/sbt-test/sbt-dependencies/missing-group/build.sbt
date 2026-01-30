lazy val defined = project

lazy val undefined = project  // No matching group in YAML

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  // defined project should have exactly cats-core
  val definedDeps = (defined / libraryDependencies).value.map(_.name).sorted
  val expectedDefinedDeps = List("cats-core", "scala-library").sorted
  assert(definedDeps == expectedDefinedDeps, s"defined libraryDependencies: expected $expectedDefinedDeps but got $definedDeps")

  // undefined project should only have scala-library (sbt default)
  val undefinedDeps = (undefined / libraryDependencies).value.map(_.name).sorted
  val expectedUndefinedDeps = List("scala-library")
  assert(undefinedDeps == expectedUndefinedDeps, s"undefined libraryDependencies: expected $expectedUndefinedDeps but got $undefinedDeps")
}
