ThisBuild / scalaVersion := "2.12.20"

ThisBuild / dependencyUpdatePins += file("project/update-pins.conf").toURI

lazy val myproject = project
