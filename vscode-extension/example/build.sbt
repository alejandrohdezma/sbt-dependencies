lazy val core = project

lazy val api = project.dependsOn(core)

lazy val streaming = project.dependsOn(core)

lazy val `build-tools` = project
