lazy val myproject = project

lazy val assertTest = taskKey[Unit]("Assert all test conditions")

assertTest := {
  val deps = (myproject / libraryDependencies).value

  // cats-core should be compile scope (no config)
  val cats = deps.find(_.name == "cats-core").get
  assert(cats.configurations.isEmpty, s"cats-core should have no config, got ${cats.configurations}")

  // protobuf-java should be protobuf scope
  val protobuf = deps.find(_.name == "protobuf-java").get
  assert(protobuf.configurations == Some("protobuf"), s"protobuf-java should be protobuf, got ${protobuf.configurations}")

  // munit should be test scope
  val munit = deps.find(_.name == "munit").get
  assert(munit.configurations == Some("test"), s"munit should be test, got ${munit.configurations}")

  // servlet should be provided scope
  val servlet = deps.find(_.name == "javax.servlet-api").get
  assert(servlet.configurations == Some("provided"), s"servlet should be provided, got ${servlet.configurations}")
}
