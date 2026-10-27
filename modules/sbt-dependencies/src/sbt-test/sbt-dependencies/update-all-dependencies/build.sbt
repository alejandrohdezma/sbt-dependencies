ThisBuild / dependencyUpdatePins         += file("project/update-pins.conf").toURI
ThisBuild / dependencyPostUpdateHooks    += file("project/post-update-hooks.conf").toURI
ThisBuild / dependencyScalafixMigrations += file("project/scalafix-migrations.conf").toURI

lazy val myproject = project
  .settings(dependenciesCheck += { (deps: List[ModuleID]) =>
    if (deps.exists(m => m.name.startsWith("http4s-core") && m.revision == "0.21.34"))
      throw new MessageOnlyException("dependenciesCheck triggered: found http4s-core 0.21.34")
  })
