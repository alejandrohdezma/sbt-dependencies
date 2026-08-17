import org.jetbrains.sbtidea.AbstractSbtIdeaPlugin
import org.jetbrains.sbtidea.Keys._
import org.jetbrains.sbtidea.packaging.PackagingKeys._
import org.jetbrains.sbtidea.pluginXmlOptions
import org.jetbrains.sbtidea.tasks.PublishPlugin
import org.jetbrains.sbtidea.verifier.FailureLevel
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
    verifyPlugin           := Def.sequential(packageArtifactZip, runPluginVerifier).value,
    packageMethod          := PackagingMethod.Standalone(),
    packageLibraryMappings := Seq.empty,
    patchPluginXml         := pluginXmlOptions { xml =>
      xml.version = version.value
      xml.sinceBuild = "251"
    },
    // These levels only trip on Scala-generated forwarders for Java default methods
    // (e.g. `Navigatable.navigationRequest`), not on APIs the plugin actually uses
    pluginVerifierOptions := pluginVerifierOptions.value.copy(
      failureLevels = FailureLevel.values().toSet -
        FailureLevel.DEPRECATED_API_USAGES -
        FailureLevel.EXPERIMENTAL_API_USAGES -
        FailureLevel.INTERNAL_API_USAGES
    ),
    publishPlugin := Def.inputTaskDyn {
      val channel = spaceDelimited("<channel>").parsed
      if (isSnapshot.value)
        Def.task(streams.value.log.info("Snapshot version, skipping JetBrains Marketplace publish"))
      else
        PublishPlugin.createTask.toTask(channel.headOption.fold("")(" " + _))
    }.evaluated
  )

}
