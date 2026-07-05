ThisBuild / scalaVersion := "2.13.16"

ThisBuild / dependencyCooldowns += file("project/cooldown.conf").toURI

lazy val myproject = project
