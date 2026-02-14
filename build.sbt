ThisBuild / scalaVersion                  := _root_.scalafix.sbt.BuildInfo.scala212
ThisBuild / organization                  := "com.alejandrohdezma"
ThisBuild / pluginCrossBuild / sbtVersion := "1.4.0"
ThisBuild / versionPolicyIntention        := Compatibility.None

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
  .settings(scriptedBufferLog := !sys.env.contains("CI"))
  .enablePlugins(BuildInfoPlugin)
  .settings(buildInfoKeys := Seq[BuildInfoKey](version))
  .settings(buildInfoPackage := "com.alejandrohdezma.sbt.dependencies")
