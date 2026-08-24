lazy val myproject    = project
lazy val otherproject = project

@transient lazy val assertBomManaged = taskKey[Unit]("Assert pinned versions were replaced with `*`")

assertBomManaged := {
  val content = IO.read((ThisBuild / baseDirectory).value / "project" / "dependencies.conf")

  val expected = List(
    // pinned by jackson-bom (common-settings) at the same version
    "com.fasterxml.jackson.core:jackson-databind:*",
    // the `=` marker is not an opt-out — it drops with the version
    "com.fasterxml.jackson.core:jackson-core:*",
    // already BOM-managed, left as is
    "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:*",
    // cross-compiled dep matched against the `_2.13`-suffixed pin, even with an older version
    "com.fasterxml.jackson.module::jackson-module-scala:*",
    // BOM coordinates keep their version
    "org.junit:junit-bom:5.10.3:bom",
    // pinned by myproject's own junit-bom
    "org.junit.jupiter:junit-jupiter-api:*:test",
    // not pinned by any visible BOM
    "org.scalameta::munit:1.0.0:test",
    // otherproject sees common-settings' jackson-bom too (proves aggregation covers every group)
    "com.fasterxml.jackson.core:jackson-annotations:*"
  )

  expected.foreach { line =>
    assert(content.contains(line), s"Expected `$line` in dependencies.conf, got:\n$content")
  }

  assert(!content.contains("=2.17.2"), s"Expected the `=` marker to be dropped, got:\n$content")
}
