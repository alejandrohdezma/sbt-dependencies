lazy val myproject = project

lazy val assertTest = taskKey[Unit]("Assert cats-effect was installed with a version")

assertTest := {
  val content = IO.read(baseDirectory.value / "project" / "dependencies.conf")

  // Verify cats-effect is present with a version (format: cats-effect:X.Y.Z)
  assert(content.contains("cats-effect:"), s"cats-effect should be in dependencies.conf, content: $content")

  // Verify it has a version number (not just "cats-effect:" at end of line)
  val catsEffectLine = content.linesIterator.find(_.contains("cats-effect")).get
  assert(
    catsEffectLine.matches(".*cats-effect:\\d+\\.\\d+\\.\\d+.*"),
    s"cats-effect should have a version number, got: $catsEffectLine"
  )
}
