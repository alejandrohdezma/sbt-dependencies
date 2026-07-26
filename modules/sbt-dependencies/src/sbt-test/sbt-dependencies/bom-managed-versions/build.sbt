lazy val myproject    = project
lazy val otherproject = project

@transient lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  def coords(deps: Seq[ModuleID]) = deps.map(m => (m.organization, m.name, m.revision)).toSet

  val myLibs = coords((myproject / libraryDependencies).value)

  // `*` on a Java dep resolves to the version pinned by myproject's own junit-bom
  assert(
    myLibs.contains(("org.junit.jupiter", "junit-jupiter-api", "5.10.3")),
    s"junit-jupiter-api should resolve to 5.10.3 from junit-bom, got: $myLibs"
  )
  // a `*` dep declared in common-settings resolves against common-settings' jackson-bom in every project
  assert(
    myLibs.contains(("com.fasterxml.jackson.core", "jackson-databind", "2.17.2")),
    s"jackson-databind should resolve to 2.17.2 from jackson-bom, got: $myLibs"
  )
  // `*` on a cross-compiled dep matches the `_2.13`-suffixed BOM entry
  assert(
    myLibs.contains(("com.fasterxml.jackson.module", "jackson-module-scala", "2.17.2")),
    s"jackson-module-scala should resolve to 2.17.2 from jackson-bom, got: $myLibs"
  )

  val myModuleScala = (myproject / libraryDependencies).value
    .find(_.name == "jackson-module-scala")
    .getOrElse(sys.error("jackson-module-scala not found in libraryDependencies"))

  assert(
    myModuleScala.crossVersion == CrossVersion.binary,
    s"jackson-module-scala should keep its binary cross-version, got: ${myModuleScala.crossVersion}"
  )

  val otherLibs = coords((otherproject / libraryDependencies).value)

  // a project without its own BOM still resolves `*` via common-settings' jackson-bom
  assert(
    otherLibs.contains(("com.fasterxml.jackson.core", "jackson-core", "2.17.2")),
    s"jackson-core should resolve to 2.17.2 from jackson-bom, got: $otherLibs"
  )
}

@transient lazy val assertFileKeepsStars = taskKey[Unit]("Assert the * versions survived updateDependencies")

assertFileKeepsStars := {
  val content = IO.read((ThisBuild / baseDirectory).value / "project" / "dependencies.conf")

  val stars = List(
    "com.fasterxml.jackson.core:jackson-databind:*",
    "com.fasterxml.jackson.module::jackson-module-scala:*",
    "org.junit.jupiter:junit-jupiter-api:*:test"
  )

  stars.foreach { line =>
    assert(content.contains(line), s"Expected `$line` to be kept in dependencies.conf, got:\n$content")
  }
}

@transient lazy val assertCommonSnapshot = taskKey[Unit]("Assert the common-settings snapshot handles * versions")

assertCommonSnapshot := {
  val snapshotFile = (ThisBuild / baseDirectory).value / "target" / "sbt-dependencies" / ".sbt-common-snapshot"

  assert(snapshotFile.exists, s"Expected ${snapshotFile.getPath} to exist")

  val content = IO.read(snapshotFile)

  assert(content.contains("jackson-databind"), s"Expected snapshot to contain jackson-databind, got:\n$content")
}
