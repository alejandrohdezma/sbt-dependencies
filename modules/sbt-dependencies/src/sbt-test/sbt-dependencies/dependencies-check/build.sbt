lazy val myproject = project
  .settings(dependenciesCheck += { (deps: List[ModuleID]) =>
    if (deps.exists(_.name.startsWith("cats-core")))
      throw new MessageOnlyException("dependenciesCheck triggered: found cats-core")
  })

lazy val otherproject = project
