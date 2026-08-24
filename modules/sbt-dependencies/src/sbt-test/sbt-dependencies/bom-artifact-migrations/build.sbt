ThisBuild / resolvers += "Test Repo" at ((ThisBuild / baseDirectory).value / "repo").toURI.toString

ThisBuild / dependencyMigrations := List(file("project/artifact-migrations.conf").toURI)

lazy val myproject = project
