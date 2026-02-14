ThisBuild / dependencyUpdatePins += file("project/update-pins.conf").toURI.toURL

lazy val myproject = project
