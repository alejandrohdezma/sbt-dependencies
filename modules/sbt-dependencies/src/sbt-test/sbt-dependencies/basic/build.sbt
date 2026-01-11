lazy val myproject = project

lazy val otherproject = project

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  // Check ThisBuild scalaVersion (from sbt-build group)
  val buildScalaVersion = (ThisBuild / scalaVersion).value
  assert(buildScalaVersion == "2.12.20", s"ThisBuild scalaVersion: expected '2.12.20' but got '$buildScalaVersion'")

  // Check myproject scalaVersion (overridden by myproject group)
  val myScalaVersion = (myproject / scalaVersion).value
  assert(myScalaVersion == "2.13.16", s"myproject scalaVersion: expected '2.13.16' but got '$myScalaVersion'")

  // Check myproject crossScalaVersions
  val myCrossVersions = (myproject / crossScalaVersions).value.toList
  assert(myCrossVersions == List("2.13.16", "2.12.20"), s"myproject crossScalaVersions: expected List(2.13.16, 2.12.20) but got $myCrossVersions")

  // Check myproject libraryDependencies
  val myDeps = (myproject / libraryDependencies).value.map(_.name).sorted
  val expectedMyDeps = List("cats-core", "munit").sorted
  assert(myDeps == expectedMyDeps, s"myproject libraryDependencies: expected $expectedMyDeps but got $myDeps")

  // Check otherproject scalaVersion (inherited from ThisBuild)
  val otherScalaVersion = (otherproject / scalaVersion).value
  assert(otherScalaVersion == "2.12.20", s"otherproject scalaVersion: expected '2.12.20' but got '$otherScalaVersion'")

  // Check otherproject libraryDependencies
  val otherDeps = (otherproject / libraryDependencies).value.map(_.name).sorted
  val expectedOtherDeps = List("cats-effect").sorted
  assert(otherDeps == expectedOtherDeps, s"otherproject libraryDependencies: expected $expectedOtherDeps but got $otherDeps")
}
