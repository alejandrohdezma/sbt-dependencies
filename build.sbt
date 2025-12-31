ThisBuild / scalaVersion                  := _root_.scalafix.sbt.BuildInfo.scala212
ThisBuild / organization                  := "com.alejandrohdezma"
ThisBuild / pluginCrossBuild / sbtVersion := "1.4.0"
ThisBuild / versionPolicyIntention        := Compatibility.BinaryAndSourceCompatible

// Simplify testing the plugin in its own build
addCommandAlias("reloadSelf", "reload; clean; publishLocal; updateSbtDependenciesPlugin; reload")

addCommandAlias("ci-test", "fix --check; versionPolicyCheck; mdoc; scripted")
addCommandAlias("ci-docs", "github; mdoc; headerCreateAll")
addCommandAlias("ci-publish", "versionCheck; github; ci-release")

lazy val documentation = project
  .enablePlugins(MdocPlugin)

lazy val `sbt-dependencies` = module
  .enablePlugins(SbtPlugin)
  .settings(scriptedLaunchOpts += s"-Dplugin.version=${version.value}")
  .settings(scriptedBufferLog := false)
  .settings(Compile / sourceGenerators += buildInfo.taskValue)

val buildInfo = Def.task {
  val file = (Compile / sourceManaged).value / "BuildInfo.scala"

  IO.write(
    file,
    s"""package com.alejandrohdezma.sbt.dependencies
       |
       |object BuildInfo {
       |  val version: String = "${version.value}"
       |}
       |""".stripMargin
  )

  Seq(file)
}
