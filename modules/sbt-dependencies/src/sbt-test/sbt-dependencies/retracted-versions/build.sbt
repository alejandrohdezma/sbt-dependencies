ThisBuild / scalaVersion := "2.12.20"

ThisBuild / dependencyUpdateRetractions += file("project/retracted.conf").toURI

lazy val myproject = project
