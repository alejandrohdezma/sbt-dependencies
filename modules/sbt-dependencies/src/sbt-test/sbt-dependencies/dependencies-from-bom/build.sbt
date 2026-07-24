lazy val myproject    = project
lazy val otherproject = project

@transient lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  def bomCoords(deps: Seq[ModuleID]) = deps.map(m => (m.organization, m.name, m.revision)).toSet
  def libNames(deps: Seq[ModuleID])  = deps.map(_.name).toSet

  val myBom  = bomCoords((myproject / dependenciesFromBom).value)
  val myLibs = libNames((myproject / libraryDependencies).value)

  // myproject's own `:bom` (junit-bom) is flattened into dependenciesFromBom
  assert(
    myBom.contains(("org.junit.jupiter", "junit-jupiter-api", "5.10.2")),
    s"myproject/dependenciesFromBom should contain junit-jupiter-api 5.10.2 from its junit-bom, got: $myBom"
  )
  // common-settings' `:bom` (jackson-bom) is inherited too
  assert(
    myBom.contains(("com.fasterxml.jackson.core", "jackson-databind", "2.17.1")),
    s"myproject/dependenciesFromBom should inherit jackson-databind 2.17.1 from common-settings, got: $myBom"
  )
  // the BOM coordinates themselves never reach libraryDependencies
  assert(!myLibs.contains("junit-bom"), s"myproject/libraryDependencies should not contain junit-bom, got: $myLibs")
  assert(!myLibs.contains("jackson-bom"), s"myproject/libraryDependencies should not contain jackson-bom, got: $myLibs")

  val otherBom  = bomCoords((otherproject / dependenciesFromBom).value)
  val otherLibs = libNames((otherproject / libraryDependencies).value)

  // otherproject declares no `:bom`, but inherits common-settings' jackson-bom
  assert(
    otherBom.contains(("com.fasterxml.jackson.core", "jackson-databind", "2.17.1")),
    s"otherproject/dependenciesFromBom should inherit jackson-databind 2.17.1 from common-settings, got: $otherBom"
  )
  // junit-bom lives only in myproject's group, so otherproject must not see it
  assert(
    !otherBom.exists { case (_, name, _) => name == "junit-jupiter-api" },
    s"otherproject/dependenciesFromBom should not contain junit-jupiter-api, got: $otherBom"
  )
  assert(
    !otherLibs.contains("jackson-bom"),
    s"otherproject/libraryDependencies should not contain jackson-bom, got: $otherLibs"
  )
}
