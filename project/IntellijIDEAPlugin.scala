import org.jetbrains.sbtidea.AbstractSbtIdeaPlugin
import org.jetbrains.sbtidea.Keys._
import org.jetbrains.sbtidea.packaging.PackagingKeys._
import org.jetbrains.sbtidea.pluginXmlOptions
import org.jetbrains.sbtidea.tasks.PublishPlugin
import sbt.Keys._
import sbt._
import sbt.complete.DefaultParsers.spaceDelimited

object IntellijIDEAPlugin extends AbstractSbtIdeaPlugin {

  override def buildSettings = super.buildSettings ++ Seq(
    intellijPluginName := "sbt-dependencies",
    intellijBuild      := "251.29188.72"
  )

  override def projectSettings = super.projectSettings ++ Seq(
    packageMethod          := PackagingMethod.Standalone(),
    packageLibraryMappings := Seq.empty,
    patchPluginXml         := pluginXmlOptions { xml =>
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
