lazy val myproject = project

lazy val assertTest = taskKey[Unit]("Assert filtering worked correctly")

assertTest := {
  val file = baseDirectory.value / "project" / "dependencies.yaml"
  val content = IO.read(file)
  
  // cats-effect should have been updated (no longer 3.4.0)
  assert(!content.contains("cats-effect:3.4.0"), 
    s"cats-effect should have been updated from 3.4.0, content: $content")
  
  // cats-core should NOT have been updated (still =2.9.0)
  assert(content.contains("cats-core:=2.9.0"), 
    s"cats-core should still be pinned at =2.9.0, content: $content")
  
  // munit should NOT have been updated (still =1.0.0)
  assert(content.contains("munit:=1.0.0"), 
    s"munit should still be pinned at =1.0.0, content: $content")
}
