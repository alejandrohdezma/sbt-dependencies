lazy val myproject = project

lazy val assertTest = taskKey[Unit]("Assert version markers work correctly")

assertTest := {
  val yamlContent = IO.read(file("project/dependencies.yaml"))

  // = marker: cats-core should stay exactly at =2.9.0
  assert(
    yamlContent.contains("cats-core:=2.9.0"),
    s"Exact marker (=) failed: cats-core should remain at =2.9.0 but file contains:\n$yamlContent"
  )

  // ^ marker: cats-effect should stay within major version 3
  // Pattern: cats-effect:^3.X.Y where X >= 4
  val majorPattern = """cats-effect:\^3\.(\d+)\.(\d+)""".r
  val majorMatch = majorPattern.findFirstMatchIn(yamlContent)
  assert(
    majorMatch.isDefined,
    s"Major marker (^) failed: cats-effect should have ^3.x.y format but file contains:\n$yamlContent"
  )
  val minorVersion = majorMatch.get.group(1).toInt
  assert(
    minorVersion >= 4,
    s"Major marker (^) failed: cats-effect minor version should be >= 4 (was updated) but got $minorVersion"
  )

  // ~ marker: munit should stay within minor version 1.0
  // Pattern: munit:~1.0.X where X >= 0
  val minorPattern = """munit:~1\.0\.(\d+)""".r
  val minorMatch = minorPattern.findFirstMatchIn(yamlContent)
  assert(
    minorMatch.isDefined,
    s"Minor marker (~) failed: munit should have ~1.0.x format but file contains:\n$yamlContent"
  )
}
