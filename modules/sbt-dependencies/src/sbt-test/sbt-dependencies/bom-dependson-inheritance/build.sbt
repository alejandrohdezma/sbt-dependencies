lazy val core = project

lazy val app = project.dependsOn(core)

@transient lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  def coords(deps: Seq[ModuleID]) = deps.map(m => (m.organization, m.name, m.revision)).toSet

  val coreLibs = coords((core / libraryDependencies).value)

  // core resolves `*` against its own junit-bom
  assert(
    coreLibs.contains(("org.junit.jupiter", "junit-jupiter-api", "5.10.1")),
    s"junit-jupiter-api should resolve to 5.10.1 from core's junit-bom, got: $coreLibs"
  )

  val appLibs = coords((app / libraryDependencies).value)

  // app declares no BOM of its own: its `*` resolves against the junit-bom inherited from core via dependsOn
  assert(
    appLibs.contains(("org.junit.jupiter", "junit-jupiter-params", "5.10.1")),
    s"junit-jupiter-params should resolve to 5.10.1 from the BOM inherited from core, got: $appLibs"
  )
  // the BOM coordinate itself never reaches libraryDependencies
  assert(
    !appLibs.exists { case (_, name, _) => name == "junit-bom" },
    s"junit-bom should not be in app's libraryDependencies, got: $appLibs"
  )
}
