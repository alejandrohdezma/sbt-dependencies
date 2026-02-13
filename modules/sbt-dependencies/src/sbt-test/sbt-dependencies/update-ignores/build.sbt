ThisBuild / dependencyUpdateIgnores += file("project/update-ignores.conf").toURI.toURL

lazy val myproject = project
