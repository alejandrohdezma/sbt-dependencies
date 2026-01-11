lazy val core = project

lazy val app = project.dependsOn(core)

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  // core should have exactly cats-core
  val coreDeps = (core / libraryDependencies).value.map(_.name).sorted
  val expectedCoreDeps = List("cats-core")
  assert(coreDeps == expectedCoreDeps, s"core libraryDependencies: expected $expectedCoreDeps but got $coreDeps")

  // app should inherit cats-core from core
  val appInherited = (app / inheritedDependencies).value.map(_.name).sorted
  val expectedAppInherited = List("cats-core")
  assert(appInherited == expectedAppInherited, s"app inheritedDependencies: expected $expectedAppInherited but got $appInherited")

  // app should have its own cats-effect
  val appDeps = (app / libraryDependencies).value.map(_.name).sorted
  val expectedAppDeps = List("cats-effect")
  assert(appDeps == expectedAppDeps, s"app libraryDependencies: expected $expectedAppDeps but got $appDeps")
}
