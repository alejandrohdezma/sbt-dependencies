lazy val myproject    = project
lazy val otherproject = project

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  val myJavac    = (myproject / Compile / javacOptions).value
  val myScalac   = (myproject / Compile / scalacOptions).value
  val otherJavac = (otherproject / Compile / javacOptions).value
  val otherScalac = (otherproject / Compile / scalacOptions).value

  // myproject inherits from sbt-build's java-version = "17"
  assert(
    myJavac.containsSlice(Seq("--release", "17")),
    s"myproject javacOptions should contain '--release 17' (sbt-build fallback), got: $myJavac"
  )
  assert(
    myScalac.contains("-release:17"),
    s"myproject scalacOptions should contain '-release:17' (sbt-build fallback), got: $myScalac"
  )

  // otherproject overrides with its own java-version = "21"
  assert(
    otherJavac.containsSlice(Seq("--release", "21")),
    s"otherproject javacOptions should contain '--release 21' (own value), got: $otherJavac"
  )
  assert(
    !otherJavac.containsSlice(Seq("--release", "17")),
    s"otherproject javacOptions should NOT contain '--release 17' (overridden), got: $otherJavac"
  )
  assert(
    otherScalac.contains("-release:21"),
    s"otherproject scalacOptions should contain '-release:21' (own value), got: $otherScalac"
  )
}
