lazy val myproject = project

def resolved(report: UpdateReport, name: String): String =
  report.allModules
    .find(m => m.organization.startsWith("com.fasterxml.jackson") && m.name == name)
    .getOrElse(sys.error(s"$name not found in the update report"))
    .revision

@transient lazy val assertShadowed = taskKey[Unit]("Assert explicit lines shadow the overrides inherited from common-settings")

assertShadowed := {
  val report = (myproject / update).value

  // both are declared by the project without the flag, so the BOM inherited from common-settings must not force them
  assert(resolved(report, "jackson-databind") == "2.17.2", "an explicit jackson-databind line should keep its version")
  assert(resolved(report, "jackson-module-scala_2.13") == "2.17.2", "an explicit jackson-module-scala line should keep its version")

  // a transitive module nobody declares is still forced to the BOM's version
  assert(resolved(report, "jackson-core") == "2.17.0", "jackson-core should be forced to 2.17.0 by the inherited BOM")

  val overrides = (myproject / dependencyOverridesFromFile).value

  assert(overrides.exists(_.name == "jackson-core"), s"overrides should contain jackson-core, got: $overrides")

  assert(
    !overrides.exists(m => m.name == "jackson-databind" || m.name == "jackson-module-scala_2.13"),
    s"overrides should not contain modules the project declares itself, got: $overrides"
  )
}
