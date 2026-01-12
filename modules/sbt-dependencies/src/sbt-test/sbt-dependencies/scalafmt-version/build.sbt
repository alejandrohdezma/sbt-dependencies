lazy val root = project.in(file("."))

lazy val assertTest = taskKey[Unit]("Assert scalafmt was updated")

assertTest := {
  val file = baseDirectory.value / ".scalafmt.conf"
  val content = IO.read(file)

  // Version should have been updated from 3.7.0
  assert(!content.contains("version = \"3.7.0\""),
    s"scalafmt version should have been updated from 3.7.0, content: $content")

  // Should still contain version field
  assert(content.contains("version"),
    s"scalafmt version field should exist, content: $content")

  // Should still contain runner.dialect (wasn't touched)
  assert(content.contains("runner.dialect = scala213"),
    s"runner.dialect should still exist, content: $content")
}
