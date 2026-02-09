ThisBuild / dependencyMigrations := List(file("project/artifact-migrations.conf").toURI.toURL)

lazy val myproject = project
