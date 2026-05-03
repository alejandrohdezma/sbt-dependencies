lazy val myproject = project

lazy val otherproject = project

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  // Build-level scalaVersion should be the first version from common-settings
  val buildScalaVersion = (ThisBuild / scalaVersion).value
  assert(buildScalaVersion == "2.13.16", s"ThisBuild scalaVersion: expected '2.13.16' but got '$buildScalaVersion'")

  val buildCross = (ThisBuild / crossScalaVersions).value.toList
  assert(
    buildCross == List("2.13.16", "2.12.20"),
    s"ThisBuild crossScalaVersions: expected List(2.13.16, 2.12.20) but got $buildCross"
  )

  // myproject inherits the common-settings versions
  val myScalaVersion = (myproject / scalaVersion).value
  assert(myScalaVersion == "2.13.16", s"myproject scalaVersion: expected '2.13.16' but got '$myScalaVersion'")

  val myCross = (myproject / crossScalaVersions).value.toList
  assert(
    myCross == List("2.13.16", "2.12.20"),
    s"myproject crossScalaVersions: expected List(2.13.16, 2.12.20) but got $myCross"
  )

  // otherproject overrides with its own scala-version
  val otherScalaVersion = (otherproject / scalaVersion).value
  assert(otherScalaVersion == "3.3.7", s"otherproject scalaVersion: expected '3.3.7' but got '$otherScalaVersion'")

  val otherCross = (otherproject / crossScalaVersions).value.toList
  assert(otherCross == List("3.3.7"), s"otherproject crossScalaVersions: expected List(3.3.7) but got $otherCross")
}
