lazy val myproject = project

lazy val optin = project

lazy val filtered = project.settings(bomOverridesFilter := { case m if m.name == "jackson-databind" => false })

lazy val pinned = project

def resolved(report: UpdateReport, name: String): String =
  report.allModules
    .find(m => m.organization.startsWith("com.fasterxml.jackson") && m.name == name)
    .getOrElse(sys.error(s"$name not found in the update report"))
    .revision

@transient lazy val assertNotOverridden = taskKey[Unit]("Assert a plain BOM line adds nothing to dependencyOverrides")

assertNotOverridden := {
  val report = (myproject / update).value

  // jackson-module-scala 2.17.2 pulls jackson-databind 2.17.2; the BOM's 2.17.0 pin only applies to `*` lines
  assert(resolved(report, "jackson-databind") == "2.17.2", "jackson-databind should not be forced without overrides")

  val overrides = (myproject / dependencyOverridesFromFile).value

  assert(overrides.isEmpty, s"myproject should declare no overrides, got: $overrides")
}

@transient lazy val assertOverridden = taskKey[Unit]("Assert a BOM line with overrides = true forces its pins")

assertOverridden := {
  val report = (optin / update).value

  assert(resolved(report, "jackson-databind") == "2.17.0", "jackson-databind should be forced to 2.17.0 by the BOM")

  // the project declares jackson-module-scala itself without the flag, so the BOM pin does not override it
  assert(resolved(report, "jackson-module-scala_2.13") == "2.17.2", "an explicit line should shadow the BOM pin")

  val overrides = (optin / dependencyOverridesFromFile).value

  assert(overrides.exists(_.name == "jackson-databind"), s"overrides should contain jackson-databind, got: $overrides")

  assert(
    !overrides.exists(_.name == "jackson-module-scala_2.13"),
    s"overrides should not contain the explicitly declared jackson-module-scala, got: $overrides"
  )
}

@transient lazy val assertFiltered = taskKey[Unit]("Assert bomOverridesFilter drops pins from a flagged BOM")

assertFiltered := {
  val report = (filtered / update).value

  assert(resolved(report, "jackson-databind") == "2.17.2", "the filtered pin should not force jackson-databind")

  assert(resolved(report, "jackson-core") == "2.17.0", "pins the filter keeps should still be forced")
}

@transient lazy val assertPinned = taskKey[Unit]("Assert a dependency line with overrides = true forces its revision")

assertPinned := {
  val report = (pinned / update).value

  assert(resolved(report, "jackson-databind") == "2.17.0", "jackson-databind should be forced to the declared 2.17.0")

  val overrides = (pinned / dependencyOverridesFromFile).value

  assert(
    overrides == Seq("com.fasterxml.jackson.core" % "jackson-databind" % "2.17.0"),
    s"overrides should hold the flagged line only, got: $overrides"
  )
}
