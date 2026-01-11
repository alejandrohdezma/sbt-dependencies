lazy val myproject = project

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  val deps = (myproject / libraryDependencies).value.map(_.name).sorted

  // With scalaVersion 2.13.x, only _2.13 and non-suffixed deps should be included
  // cats-core_2.13 should be included
  assert(deps.contains("cats-core_2.13"), s"cats-core_2.13 should be included, got: $deps")

  // cats-effect (no suffix, cross-compiled) should be included
  assert(deps.contains("cats-effect"), s"cats-effect should be included, got: $deps")

  // cats-core_3 should NOT be included (wrong Scala version)
  assert(!deps.contains("cats-core_3"), s"cats-core_3 should NOT be included, got: $deps")
}
