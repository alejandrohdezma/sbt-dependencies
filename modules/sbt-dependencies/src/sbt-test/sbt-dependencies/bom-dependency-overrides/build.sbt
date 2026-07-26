lazy val myproject = project

lazy val optout = project.settings(dependencyOverridesFromBom := Nil)

@transient lazy val assertOverridden = taskKey[Unit]("Assert transitive dependencies are forced to BOM versions")

assertOverridden := {
  val databind = (myproject / update).value.allModules
    .find(m => m.organization == "com.fasterxml.jackson.core" && m.name == "jackson-databind")
    .getOrElse(sys.error("jackson-databind not found in myproject's update report"))

  // jackson-module-scala 2.17.2 pulls jackson-databind 2.17.2, but the BOM pin forces the downgrade
  assert(
    databind.revision == "2.17.0",
    s"jackson-databind should be forced to 2.17.0 by jackson-bom, got: ${databind.revision}"
  )
}

@transient lazy val assertNotOverridden = taskKey[Unit]("Assert opting out leaves transitive resolution untouched")

assertNotOverridden := {
  val databind = (optout / update).value.allModules
    .find(m => m.organization == "com.fasterxml.jackson.core" && m.name == "jackson-databind")
    .getOrElse(sys.error("jackson-databind not found in optout's update report"))

  assert(
    databind.revision == "2.17.2",
    s"jackson-databind should resolve to 2.17.2 without BOM overrides, got: ${databind.revision}"
  )

  val jacksonOverrides = (optout / dependencyOverrides).value.filter(_.organization.startsWith("com.fasterxml.jackson"))

  assert(
    jacksonOverrides.isEmpty,
    s"optout should have no jackson pins in dependencyOverrides, got: $jacksonOverrides"
  )
}

@transient lazy val assertDeduped = taskKey[Unit]("Assert BOM pins are deduplicated by module keeping the first entry")

assertDeduped := {
  val pins = (myproject / dependencyOverridesFromBom).value

  assert(pins.nonEmpty, "myproject should have BOM pins in dependencyOverridesFromBom")

  val duplicated = pins.groupBy(m => (m.organization, m.name)).filter(_._2.size > 1)

  assert(duplicated.isEmpty, s"dependencyOverridesFromBom should have one entry per module, got duplicates: $duplicated")
}
