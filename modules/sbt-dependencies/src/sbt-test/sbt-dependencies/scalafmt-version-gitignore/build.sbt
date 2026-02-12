lazy val root = project.in(file("."))

lazy val assertTest = taskKey[Unit]("Assert only non-ignored scalafmt was updated")

assertTest := {
  val rootConf = baseDirectory.value / ".scalafmt.conf"
  val rootContent = IO.read(rootConf)

  // Root .scalafmt.conf is git-ignored, so it should NOT have been updated
  assert(rootContent.contains("version = \"3.7.0\""),
    s"root .scalafmt.conf should still be 3.7.0 (git-ignored), content: $rootContent")

  val resourcesConf = baseDirectory.value / "src" / "main" / "resources" / ".scalafmt.conf"
  val resourcesContent = IO.read(resourcesConf)

  // Resources .scalafmt.conf is NOT ignored, so it should have been updated
  assert(!resourcesContent.contains("version = \"3.7.0\""),
    s"resources .scalafmt.conf should have been updated from 3.7.0, content: $resourcesContent")

  assert(resourcesContent.contains("version"),
    s"resources .scalafmt.conf should still contain version field, content: $resourcesContent")

  assert(resourcesContent.contains("runner.dialect = scala213"),
    s"resources .scalafmt.conf should still contain runner.dialect, content: $resourcesContent")
}
