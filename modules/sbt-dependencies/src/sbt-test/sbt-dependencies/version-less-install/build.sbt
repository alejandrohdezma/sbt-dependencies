lazy val myproject = project

@transient lazy val assertTest = taskKey[Unit]("Assert the installed dependencies got a version")

assertTest := {
  val content = IO.read((ThisBuild / baseDirectory).value / "project" / "dependencies.conf")

  // Verify cats-effect is present with a version (format: cats-effect:X.Y.Z)
  assert(content.contains("cats-effect:"), s"cats-effect should be in dependencies.conf, content: $content")

  // Verify it has a version number (not just "cats-effect:" at end of line)
  val catsEffectLine = content.linesIterator.find(_.contains("cats-effect")).get
  assert(
    catsEffectLine.matches(".*cats-effect:\\d+\\.\\d+\\.\\d+.*"),
    s"cats-effect should have a version number, got: $catsEffectLine"
  )

  // jbcrypt's only stable release is the 2-part 0.4, so this checks a non-3-part version resolves
  assert(
    content.contains("org.mindrot:jbcrypt:0.4"),
    s"jbcrypt should be in dependencies.conf at 0.4, content: $content"
  )
}
