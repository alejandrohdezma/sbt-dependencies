lazy val myproject = project

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  val deps = (myproject / libraryDependencies).value

  val plugin = deps.find(_.name == "kind-projector").getOrElse(
    sys.error(s"kind-projector not found in libraryDependencies: ${deps.map(_.name).mkString(", ")}")
  )

  assert(
    plugin.configurations == Some("plugin->default(compile)"),
    s"kind-projector configuration: expected Some(plugin->default(compile)) but got ${plugin.configurations}"
  )

  // The `cross-version = "full"` annotation overrides the default (binary) → expect Full.
  assert(
    plugin.crossVersion.isInstanceOf[sbt.librarymanagement.CrossVersion.Full],
    s"kind-projector crossVersion: expected Full but got ${plugin.crossVersion}"
  )
}
