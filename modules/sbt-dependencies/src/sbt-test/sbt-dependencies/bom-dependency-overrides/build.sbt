lazy val myproject = project

lazy val optin = project.settings(dependencyOverrides ++= dependenciesFromBom.value)

@transient lazy val assertNotOverridden = taskKey[Unit]("Assert BOM pins don't reach dependencyOverrides by default")

assertNotOverridden := {
  val databind = (myproject / update).value.allModules
    .find(m => m.organization == "com.fasterxml.jackson.core" && m.name == "jackson-databind")
    .getOrElse(sys.error("jackson-databind not found in myproject's update report"))

  // jackson-module-scala 2.17.2 pulls jackson-databind 2.17.2; the BOM's 2.17.0 pin only applies to `*` lines
  assert(
    databind.revision == "2.17.2",
    s"jackson-databind should resolve to 2.17.2 without BOM overrides, got: ${databind.revision}"
  )

  val jacksonOverrides = (myproject / dependencyOverrides).value.filter(_.organization.startsWith("com.fasterxml.jackson"))

  assert(
    jacksonOverrides.isEmpty,
    s"myproject should have no jackson pins in dependencyOverrides, got: $jacksonOverrides"
  )

  val pins = (myproject / dependenciesFromBom).value

  assert(pins.nonEmpty, "myproject should expose BOM pins through dependenciesFromBom")

  val duplicated = pins.groupBy(m => (m.organization, m.name)).filter(_._2.size > 1)

  assert(duplicated.isEmpty, s"dependenciesFromBom should have one entry per module, got duplicates: $duplicated")
}

@transient lazy val assertOverridden = taskKey[Unit]("Assert opting in forces transitive dependencies to BOM versions")

assertOverridden := {
  val databind = (optin / update).value.allModules
    .find(m => m.organization == "com.fasterxml.jackson.core" && m.name == "jackson-databind")
    .getOrElse(sys.error("jackson-databind not found in optin's update report"))

  assert(
    databind.revision == "2.17.0",
    s"jackson-databind should be forced to 2.17.0 by jackson-bom, got: ${databind.revision}"
  )
}
