lazy val myproject = project

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

myproject / assertTest := {
  val deps       = (myproject / libraryDependencies).value
  val reflectDep = deps.find(_.name == "scala-reflect")

  assert(reflectDep.isDefined, "scala-reflect dependency not found")
  assert(reflectDep.get.revision == "2.13.18", s"Expected scala-reflect:2.13.18, got ${reflectDep.get.revision}")
}
