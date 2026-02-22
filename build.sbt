ThisBuild / scalaVersion                  := _root_.scalafix.sbt.BuildInfo.scala212
ThisBuild / organization                  := "com.alejandrohdezma"
ThisBuild / pluginCrossBuild / sbtVersion := "1.4.0"
ThisBuild / versionPolicyIntention        := Compatibility.None

ThisBuild / fileTransformers += ".gitignore" -> { (content: String) =>
  content + """
              |### sbt-dependencies scripted tests ###
              |# Prevent updateScalafmtVersion from modifying .scalafmt.conf inside scripted test directories
              |
              |**/src/sbt-test/**/.scalafmt.conf""".stripMargin
}

// Simplify testing the plugin in its own build
addCommandAlias("reloadSelf", "reload; clean; publishLocal; updateSbtPlugin; reload")

addCommandAlias("ci-test", "fix --check; versionPolicyCheck; mdoc; scripted")
addCommandAlias("ci-docs", "github; mdoc; headerCreateAll")
addCommandAlias("ci-publish", "versionCheck; github; ci-release")

lazy val documentation = project
  .enablePlugins(MdocPlugin)

lazy val `sbt-dependencies` = module
  .enablePlugins(SbtPlugin)
  .settings(scriptedLaunchOpts += s"-Dplugin.version=${version.value}")
  .settings(scriptedBufferLog := false)
  .settings(scriptedBatchExecution := true)
  .settings(scriptedParallelInstances := 5)
  .enablePlugins(BuildInfoPlugin)
  .settings(buildInfoKeys := Seq[BuildInfoKey](version))
  .settings(buildInfoPackage := "com.alejandrohdezma.sbt.dependencies")
