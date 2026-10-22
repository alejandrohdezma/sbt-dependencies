lazy val myproject = project

@transient lazy val assertTest = taskKey[Unit]("Assert only the filtered dependencies were updated")

assertTest := {
  val file    = (ThisBuild / baseDirectory).value / "project" / "dependencies.conf"
  val content = IO.read(file)

  assert(!content.contains("cats-effect:3.4.0"), s"cats-effect should have been updated in both groups: $content")

  assert(
    "cats-core:2.9.0".r.findAllMatchIn(content).size == 2,
    s"cats-core should still be at 2.9.0 in both groups: $content"
  )
}
