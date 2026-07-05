ThisBuild / scalaVersion := "2.12.20"

ThisBuild / dependencyUpdateIgnores += file("project/update-ignores.conf").toURI

lazy val myproject = project
