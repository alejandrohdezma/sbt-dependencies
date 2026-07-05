ThisBuild / dependencyUpdatePins         += file("project/update-pins.conf").toURI
ThisBuild / dependencyPostUpdateHooks    += file("project/post-update-hooks.conf").toURI
ThisBuild / dependencyScalafixMigrations += file("project/scalafix-migrations.conf").toURI

lazy val myproject = project
