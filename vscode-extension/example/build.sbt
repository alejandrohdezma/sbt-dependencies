// Resolver for the `{{circeVersion}}` variable used in `core`.
ThisBuild / dependencyVersionVariables := Map(
  "circeVersion" -> { artifact => artifact % "0.14.10" }
)

lazy val core = project

lazy val api = project.dependsOn(core)

lazy val streaming = project.dependsOn(core)

lazy val `build-tools` = project
