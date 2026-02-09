ThisBuild / scalaVersion       := "2.13.12"
ThisBuild / crossScalaVersions := Seq("2.13.12", "3.3.3")

lazy val myproject = project
  .settings(libraryDependencies += "org.typelevel" %% "cats-core" % "2.10.0")
  .settings(libraryDependencies += "org.scalameta" %% "munit" % "1.2.1" % Test)

lazy val otherproject = project
  .settings(libraryDependencies += "org.typelevel" %% "cats-effect" % "3.5.7")
