ThisBuild / dependencyCooldowns += file("project/cooldown.conf").toURI.toURL

lazy val myproject = project
