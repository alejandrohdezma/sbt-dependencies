lazy val myproject = project

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

myproject / assertTest := {
  val scalaV     = (myproject / scalaVersion).value
  val deps       = (myproject / libraryDependencies).value
  val reflectDep = deps.find(_.name == "scala-reflect")
  val catsDep    = deps.find(_.name == "cats-core")

  assert(catsDep.isDefined, "cats-core should always be present")

  if (scalaV.startsWith("2.")) {
    assert(reflectDep.isDefined, s"scala-reflect should be present for Scala $scalaV")
    assert(reflectDep.get.revision == scalaV, s"Expected scala-reflect:$scalaV, got ${reflectDep.get.revision}")
  } else {
    assert(reflectDep.isEmpty, s"scala-reflect should NOT be present for Scala $scalaV")
  }
}
