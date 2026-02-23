ThisBuild / dependencyUpdatePins         += file("project/update-pins.conf").toURI.toURL
ThisBuild / dependencyPostUpdateHooks    += file("project/post-update-hooks.conf").toURI.toURL
ThisBuild / dependencyScalafixMigrations += file("project/scalafix-migrations.conf").toURI.toURL

lazy val myproject = project
