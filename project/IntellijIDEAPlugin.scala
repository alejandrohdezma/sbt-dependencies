import org.jetbrains.sbtidea.AbstractSbtIdeaPlugin
import org.jetbrains.sbtidea.Keys._
import org.jetbrains.sbtidea.packaging.PackagingKeys._
import org.jetbrains.sbtidea.pluginXmlOptions
import org.jetbrains.sbtidea.tasks.PublishPlugin
import sbt.Keys._
import sbt._
import sbt.complete.DefaultParsers.spaceDelimited

object IntellijIDEAPlugin extends AbstractSbtIdeaPlugin {

  val verifyPlugin = taskKey[Unit] {
    "Packages the plugin zip and runs the IntelliJ plugin verifier against it"
  }

  override def buildSettings = super.buildSettings ++ Seq(
    intellijPluginName := "sbt-dependencies",
    intellijBuild      := "251.29188.72"
  )

  override def projectSettings = super.projectSettings ++ Seq(
    // Don't emit forwarders for inherited Java default methods: they surface as
    // deprecated/experimental platform API "usages" in the plugin verifier
    Compile / scalacOptions += "-Xmixin-force-forwarders:false",
    verifyPlugin            := Def.sequential(packageArtifactZip, runPluginVerifier).value,
    packageMethod           := PackagingMethod.Standalone(),
    packageLibraryMappings  := Seq.empty,
    patchPluginXml          := pluginXmlOptions { xml =>
      xml.version = version.value
      xml.sinceBuild = "251"
    },
    publishPlugin := Def.inputTaskDyn {
      val channel = spaceDelimited("<channel>").parsed
      if (isSnapshot.value)
        Def.task(streams.value.log.info("Snapshot version, skipping JetBrains Marketplace publish"))
      else
        PublishPlugin.createTask.toTask(channel.headOption.fold("")(" " + _))
    }.evaluated
  )

}
