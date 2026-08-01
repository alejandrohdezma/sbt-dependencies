ThisBuild / scalaVersion                  := _root_.scalafix.sbt.BuildInfo.scala212
ThisBuild / crossScalaVersions            := Seq(scalaVersion.value, "3.8.4")
ThisBuild / organization                  := "com.alejandrohdezma"
ThisBuild / pluginCrossBuild / sbtVersion := scalaVersion.value.on(2)("1.12.14").getOrElse("2.0.0")
ThisBuild / versionPolicyIntention        := Compatibility.BinaryAndSourceCompatible

ThisBuild / fileTransformers += ".gitignore" -> { (content: String) =>
  content + """
              |### sbt-dependencies scripted tests ###
              |# Prevent updateScalafmtVersion from modifying .scalafmt.conf inside scripted test directories
              |
              |**/src/sbt-test/**/.scalafmt.conf""".stripMargin
}

// Simplify testing the plugin in its own build
addCommandAlias("reloadSelf", "reload; clean; publishLocal; updateSbtPlugin; reload")

addCommandAlias("ci-test", "fix --check; +versionPolicyCheck; +test; +publishLocal; +scripted; mdoc")
addCommandAlias("ci-docs", "github; mdoc; headerCreateAll")
addCommandAlias("ci-publish", "versionCheck; github; ci-release")

lazy val documentation = project
  .enablePlugins(MdocPlugin)

lazy val `sbt-dependencies` = module
  .enablePlugins(SbtPlugin)
  .settings(scriptedLaunchOpts += s"-Dplugin.version=${version.value}")
  .settings(scriptedBufferLog := true)
  .settings(scriptedBatchExecution := true)
  .settings(scriptedParallelInstances := 5)
  .enablePlugins(BuildInfoPlugin)
  .settings(buildInfoKeys := Seq[BuildInfoKey](version))
  .settings(buildInfoPackage := "com.alejandrohdezma.sbt.dependencies")
  .settings(Test / scalacOptions ++= scalaVersion.value.on(3)("-Wconf:msg=@nowarn annotation does:s"))
