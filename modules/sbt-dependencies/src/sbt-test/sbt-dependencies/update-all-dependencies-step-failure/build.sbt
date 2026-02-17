import sbt.internal.util.MessageOnlyException

lazy val myproject = project

// Shadow the `updateScalaVersions` task (step 5) with a command that always fails.
// Commands take precedence over tasks in sbt's combined parser.
commands += Command.command("updateScalaVersions") { state =>
  throw new MessageOnlyException("Simulated failure in updateScalaVersions")
}

// Shadow the `updateDependencies` task (step 6) with a command that writes a marker file.
// If this file exists after the test, step 6 was NOT skipped (test should fail).
commands += Command.command("updateDependencies") { state =>
  val base = Project.extract(state).get(ThisBuild / baseDirectory)
  IO.write(base / "step-after-failure-ran", "")
  state
}
