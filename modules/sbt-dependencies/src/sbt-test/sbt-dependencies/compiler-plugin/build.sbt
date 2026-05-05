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

  assert(
    plugin.revision == "0.13.3",
    s"kind-projector revision: expected 0.13.3 but got ${plugin.revision}"
  )

  // Default cross-version (no annotation) is `binary` — same as `%%`.
  assert(
    plugin.crossVersion.isInstanceOf[sbt.librarymanagement.CrossVersion.Binary],
    s"kind-projector crossVersion: expected Binary but got ${plugin.crossVersion}"
  )
}
