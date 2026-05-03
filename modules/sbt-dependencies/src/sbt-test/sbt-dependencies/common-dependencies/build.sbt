lazy val myproject    = project
lazy val otherproject = project

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  // myproject inherits both common deps verbatim
  val myDeps = (myproject / libraryDependencies).value.map(m => (m.name, m.revision)).toSet
  assert(
    myDeps.contains(("cats-core", "2.10.0")),
    s"myproject should contain cats-core 2.10.0 from common-settings, got: $myDeps"
  )
  assert(
    myDeps.contains(("munit", "1.2.1")),
    s"myproject should contain munit 1.2.1 from common-settings, got: $myDeps"
  )

  // otherproject overrides cats-core with its own version, still inherits munit
  val otherDeps = (otherproject / libraryDependencies).value.map(m => (m.name, m.revision)).toSet
  assert(
    otherDeps.contains(("cats-core", "2.11.0")),
    s"otherproject should contain cats-core 2.11.0 (own override), got: $otherDeps"
  )
  assert(
    !otherDeps.contains(("cats-core", "2.10.0")),
    s"otherproject should NOT contain cats-core 2.10.0 (overridden), got: $otherDeps"
  )
  assert(
    otherDeps.contains(("munit", "1.2.1")),
    s"otherproject should still contain munit 1.2.1 from common-settings, got: $otherDeps"
  )

  // Sanity: only one cats-core entry per project (dedup worked)
  val myCatsCore    = (myproject / libraryDependencies).value.count(_.name == "cats-core")
  val otherCatsCore = (otherproject / libraryDependencies).value.count(_.name == "cats-core")
  assert(myCatsCore == 1, s"myproject should have exactly 1 cats-core entry, got $myCatsCore")
  assert(otherCatsCore == 1, s"otherproject should have exactly 1 cats-core entry, got $otherCatsCore")
}
