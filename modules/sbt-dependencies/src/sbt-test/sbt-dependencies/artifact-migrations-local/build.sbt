ThisBuild / dependencyMigrations := List(file("project/artifact-migrations.conf").toURI)

lazy val myproject = project
