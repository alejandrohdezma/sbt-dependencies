lazy val myproject = project

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  val javac  = (myproject / Compile / javacOptions).value
  val scalac = (myproject / Compile / scalacOptions).value

  assert(
    javac.containsSlice(Seq("--release", "17")),
    s"javacOptions should contain '--release 17', got: $javac"
  )

  assert(
    scalac.contains("-release:17"),
    s"scalacOptions should contain '-release:17', got: $scalac"
  )
}
