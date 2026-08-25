lazy val myproject    = project
lazy val otherproject = project

@transient lazy val assertSafeRun = taskKey[Unit]("Assert the safe run kept marked lines and reported them")

assertSafeRun := {
  val content = IO.read((ThisBuild / baseDirectory).value / "project" / "dependencies.conf")

  // unmarked pinned lines are adopted, even when the pin differs from the declared version
  List(
    "com.fasterxml.jackson.core:jackson-databind:*",
    "com.fasterxml.jackson.module::jackson-module-scala:*",
    "org.junit.jupiter:junit-jupiter-api:*:test",
    "com.fasterxml.jackson.core:jackson-annotations:^2.17.1",
    "com.fasterxml.jackson.core:jackson-core:=2.17.2"
  ).foreach { line =>
    assert(content.contains(line), s"Expected `$line` in dependencies.conf after the safe run, got:\n$content")
  }

  val reports = (ThisBuild / baseDirectory).value / "target" / "sbt-dependencies" / "bom-managed"

  val myproject = IO.read(reports / "myproject.md")

  List(
    "- `myproject`: `com.fasterxml.jackson.core:jackson-databind:2.17.2` → `*` (resolves to `2.17.2`)",
    "- `myproject`: `com.fasterxml.jackson.module::jackson-module-scala:2.17.1` → `*` (resolves to `2.17.2`)",
    "- `myproject`: `com.fasterxml.jackson.core:jackson-core:=2.17.2` left as is — `=` marker (BOM pins `2.17.2`)",
    "- `myproject`: `com.fasterxml.jackson.core:jackson-annotations:^2.17.1` left as is — `^` marker (BOM pins `2.17.2`)"
  ).foreach { line =>
    assert(myproject.contains(line), s"Expected `$line` in the myproject report, got:\n$myproject")
  }

  val otherproject = IO.read(reports / "otherproject.md")

  assert(
    otherproject.contains("- `otherproject`: `com.fasterxml.jackson.core:jackson-annotations:2.17.2` → `*` (resolves to `2.17.2`)"),
    s"Expected the otherproject adoption in its report, got:\n$otherproject"
  )
}

@transient lazy val assertBomManaged = taskKey[Unit]("Assert pinned versions were replaced with `*`")

assertBomManaged := {
  val content = IO.read((ThisBuild / baseDirectory).value / "project" / "dependencies.conf")

  val expected = List(
    // pinned by jackson-bom (common-settings) at the same version
    "com.fasterxml.jackson.core:jackson-databind:*",
    // markers are not an opt-out outside safe mode — they drop with the version
    "com.fasterxml.jackson.core:jackson-core:*",
    "com.fasterxml.jackson.core:jackson-annotations:*",
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
  assert(!content.contains("^2.17.1"), s"Expected the `^` marker to be dropped, got:\n$content")

  // nothing left to report once every pinned line is `*`
  val reports = (ThisBuild / baseDirectory).value / "target" / "sbt-dependencies" / "bom-managed"
  assert(!(reports / "otherproject.md").exists, "Expected otherproject's report to be removed on a no-op run")
}
