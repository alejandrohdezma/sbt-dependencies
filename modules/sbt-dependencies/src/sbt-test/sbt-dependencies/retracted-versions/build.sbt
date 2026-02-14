ThisBuild / dependencyUpdateRetractions += file("project/retracted.conf").toURI.toURL

lazy val myproject = project
