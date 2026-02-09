Manage SBT dependencies from a single YAML file with version markers, auto-updates, and cross-project support

## Installation

Add the following line to your `project/project/plugins.sbt` file:

```sbt
addSbtPlugin("com.alejandrohdezma" % "sbt-dependencies" % "0.11.0")
```

> Adding the plugin to `project/project/plugins.sbt` (meta-build) allows it to
> manage both your build dependencies and your project dependencies.

## Usage

This plugin manages your project's dependencies through a single `project/dependencies.conf` file. Instead of declaring `libraryDependencies` and `addSbtPlugin` in your build files, you list all dependencies in HOCON format grouped by project name:

```hocon
sbt-build = [
  "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"
  "org.scalameta:sbt-scalafmt:2.5.4:sbt-plugin"
]

my-project = [
  "org.typelevel::cats-core:2.10.0"
  "org.scalameta::munit:1.2.1:test"
]
```

The plugin automatically populates `libraryDependencies` for each project based on its group and provides commands to install, update, and validate dependencies.

---

- [Installation](#installation)
- [Usage](#usage)
- [How to...](#how-to)
  + [Migrate an existing project](#user-content-migrate-an-existing-project)
  + [Define dependencies](#user-content-define-dependencies)
  + [Use cross-compiled (Scala) dependencies](#user-content-use-cross-compiled-dependencies)
  + [Filter dependencies by Scala version](#user-content-filter-by-scala-version)
  + [Pin a dependency to a specific version](#user-content-pin-a-dependency)
  + [Use shared version variables](#user-content-use-shared-version-variables)
  + [Configure Scala versions](#user-content-configure-scala-versions)
  + [Use the advanced group format](#user-content-use-advanced-group-format)
  + [Install a new dependency](#user-content-install-a-new-dependency)
  + [Install a build dependency](#user-content-install-a-build-dependency)
  + [Update project dependencies](#user-content-update-project-dependencies)
  + [Update only specific dependencies](#user-content-update-specific-dependencies)
  + [Update build dependencies](#user-content-update-build-dependencies)
  + [Update Scala versions](#user-content-update-scala-versions)
  + [Update the SBT version](#user-content-update-sbt-version)
  + [Update the scalafmt version](#user-content-update-scalafmt-version)
  + [Update the plugin itself](#user-content-update-the-plugin)
  + [Update everything at once](#user-content-update-everything)
  + [Configure artifact migrations](#user-content-configure-artifact-migrations)
  + [Show library dependencies](#user-content-show-library-dependencies)
  + [Get all resolved dependencies](#user-content-get-all-resolved-dependencies)
  + [Validate resolved dependencies](#user-content-validate-resolved-dependencies)
  + [Disable eviction warnings](#user-content-disable-eviction-warnings)

## How to...

<details><summary><b id="migrate-an-existing-project">Migrate an existing project</b></summary><br/>

Run `initDependenciesFile` to automatically generate `dependencies.conf` from your current `libraryDependencies` and `addSbtPlugin` settings:

```bash
sbt> initDependenciesFile
```

After running this command, remove the `libraryDependencies +=` and `addSbtPlugin` lines from your build files, as the plugin will now manage them via `dependencies.conf`.

---

</details>

<details><summary><b id="define-dependencies">Define dependencies</b></summary><br/>

Create a `project/dependencies.conf` file listing your dependencies. Groups correspond to:

- `sbt-build`: Dependencies for your build definition (plugins and libraries used in `build.sbt`)
- `<project-name>`: Dependencies for a specific project (matches the SBT project name)

Dependencies follow this format:

```
org::name:version:config   # Cross-compiled (Scala) dependency with configuration
org::name:version          # Cross-compiled (Scala) dependency
org::name                  # Cross-compiled, latest version resolved automatically
org:name:version:config    # Java dependency with configuration
org:name:version           # Java dependency
org:name                   # Java, latest version resolved automatically
```

Supported configurations: `compile` (default), `test`, `provided`, `sbt-plugin`, etc.

---

</details>

<details><summary><b id="use-cross-compiled-dependencies">Use cross-compiled (Scala) dependencies</b></summary><br/>

Use `::` (double colon) between organization and artifact name to indicate a Scala cross-compiled dependency. This is equivalent to `%%` in SBT:

```hocon
my-project = [
  "org.typelevel::cats-core:2.10.0"   # Cross-compiled (like org.typelevel %% "cats-core")
  "com.google.guava:guava:33.0.0"     # Java dependency (like com.google.guava % "guava")
]
```

---

</details>

<details><summary><b id="filter-by-scala-version">Filter dependencies by Scala version</b></summary><br/>

Dependencies with Scala version suffixes in their artifact name are automatically filtered based on the current `scalaVersion`:

```hocon
my-project = [
  "org.example:my-lib_2.13:1.0.0"  # Only added when scalaVersion is 2.13.x
  "org.example:my-lib_2.12:1.0.0"  # Only added when scalaVersion is 2.12.x
  "org.example:my-lib_3:1.0.0"     # Only added when scalaVersion is 3.x
  "org.example:other-lib:1.0.0"    # Always added (no suffix)
]
```

This is useful for dependencies that are published with Scala-specific variants but aren't cross-compiled in the usual way (e.g., some native libraries or Java libraries with Scala-specific modules).

---

</details>

<details><summary><b id="pin-a-dependency">Pin a dependency to a specific version</b></summary><br/>

Control how dependencies are updated using version markers:

| Marker | Example | Behavior |
|--------|---------|----------|
| (none) | `2.10.0` | Update to latest compatible version |
| `=` | `=2.10.0` | Pin to exact version, never update |
| `^` | `^2.10.0` | Update within major version only (2.x.x) |
| `~` | `~2.10.0` | Update within minor version only (2.10.x) |

```hocon
my-project = [
  "org.typelevel::cats-core:2.10.0"    # Will update to any newer version
  "org.typelevel::cats-effect:=3.5.0"  # Pinned, never updated
  "co.fs2::fs2-core:^3.9.0"           # Updated within 3.x.x only
  "io.circe::circe-core:~0.14.6"      # Updated within 0.14.x only
]
```

---

</details>

<details><summary><b id="use-shared-version-variables">Use shared version variables</b></summary><br/>

You can use variable syntax to reference versions defined (or computed) in your build:

```hocon
my-project = [
  "org.typelevel::cats-core:{{catsVersion}}"
  "org.typelevel::cats-effect:{{catsVersion}}"
]
```

Define variable resolvers in your `build.sbt`:

```scala
dependencyVersionVariables := Map(
  "catsVersion" -> { artifact => artifact % "2.10.0" }
)
```

When running `updateDependencies`, variable-based dependencies show their resolved version and the latest available version, but the variable reference is preserved in the file.

#### Using with [here-sbt-bom](https://github.com/heremaps/here-sbt-bom)

The `here-sbt-bom` plugin reads Maven BOM files and exposes version constants. You can reference these in your `dependencies.conf`:

```hocon
my-project = [
  "com.fasterxml.jackson.core:jackson-core:{{jackson}}"
  "com.fasterxml.jackson.core:jackson-databind:{{jackson}}"
]
```

```scala
// build.sbt
val jacksonBom = Bom("com.fasterxml.jackson" % "jackson-bom" % "2.14.2")

dependencyVersionVariables := Map(
  "jackson" -> { artifact => artifact % jacksonBom.key.value }
)
```

---

</details>

<details><summary><b id="configure-scala-versions">Configure Scala versions</b></summary><br/>

You can configure `scalaVersion` and `crossScalaVersions` directly in `dependencies.conf` using the [advanced format](#user-content-use-advanced-group-format):

```hocon
sbt-build {
  scala-versions = ["2.13.12", "2.12.18"]
  dependencies = [
    "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"
  ]
}

my-project {
  scala-version = "3.3.1"
  dependencies = [
    "org.typelevel::cats-core:2.10.0"
  ]
}
```

Use `scala-version` (singular) for a single version or `scala-versions` (plural) for cross-building.

**Behavior:**
- The first version becomes `scalaVersion`
- All versions become `crossScalaVersions`
- `scala-version`/`scala-versions` in the `sbt-build` group applies at the build level (`ThisBuild / scalaVersion` and `ThisBuild / crossScalaVersions`)
- `scala-version`/`scala-versions` in individual project groups overrides the build-level settings for that project

This allows you to set a default Scala version for all projects while letting specific projects use different versions.

---

</details>

<details><summary><b id="use-advanced-group-format">Use the advanced group format</b></summary><br/>

Groups support an advanced format that enables additional configuration beyond just listing dependencies:

```hocon
my-project {
  scala-versions = ["2.13.12", "2.12.18", "3.3.1"]
  dependencies = [
    "org.typelevel::cats-core:2.10.0"
    "org.scalameta::munit:1.2.1:test"
  ]
}
```

The simple format (array of dependencies) and advanced format (object with `dependencies` key) can be mixed in the same file.

---

</details>

<details><summary><b id="install-a-new-dependency">Install a new dependency</b></summary><br/>

Use `install` to add a new dependency to the current project's group in `dependencies.conf`:

```bash
sbt> install org.typelevel::cats-core:2.10.0
sbt> install org.typelevel::cats-effect  # Resolves latest version
sbt> install org.scalameta::munit:1.2.1:test
```

---

</details>

<details><summary><b id="install-a-build-dependency">Install a build dependency</b></summary><br/>

Use `installBuildDependencies` to add a new dependency to the meta-build (`sbt-build` group):

```bash
sbt> installBuildDependencies ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin
```

---

</details>

<details><summary><b id="update-project-dependencies">Update project dependencies</b></summary><br/>

Use `updateDependencies` to update dependencies in the current project to their latest versions (respecting [version markers](#user-content-pin-a-dependency)):

```bash
sbt> updateDependencies
```

---

</details>

<details><summary><b id="update-specific-dependencies">Update only specific dependencies</b></summary><br/>

Pass a filter to `updateDependencies` to update only matching dependencies:

```bash
sbt> updateDependencies org.typelevel:          # Update all org.typelevel dependencies
sbt> updateDependencies :cats-core              # Update cats-core from any organization
sbt> updateDependencies org.typelevel:cats-core # Update a specific dependency
```

---

</details>

<details><summary><b id="update-build-dependencies">Update build dependencies</b></summary><br/>

Use `updateBuildDependencies` to update dependencies in the meta-build (`project/dependencies.conf`, group `sbt-build`):

```bash
sbt> updateBuildDependencies
```

---

</details>

<details><summary><b id="update-scala-versions">Update Scala versions</b></summary><br/>

Use `updateScalaVersions` to update Scala versions in the current project to their latest versions within the same minor line:

```bash
sbt> updateScalaVersions
```

Each version is updated within its minor line:
- `2.13.12` → latest `2.13.x`
- `2.12.18` → latest `2.12.x`
- `3.3.1` → latest `3.3.x`

Use `updateBuildScalaVersions` to update Scala versions in the `sbt-build` group (build-level settings):

```bash
sbt> updateBuildScalaVersions
```

---

</details>

<details><summary><b id="update-sbt-version">Update the SBT version</b></summary><br/>

Use `updateSbt` to update the SBT version in `project/build.properties` to the latest version. If updated, it triggers a reboot to apply the new version.

```bash
sbt> updateSbt
```

---

</details>

<details><summary><b id="update-scalafmt-version">Update the scalafmt version</b></summary><br/>

Use `updateScalafmtVersion` to update the scalafmt version in `.scalafmt.conf` to the latest version:

```bash
sbt> updateScalafmtVersion
```

---

</details>

<details><summary><b id="update-the-plugin">Update the plugin itself</b></summary><br/>

Use `updateSbtPlugin` to update the `sbt-dependencies` plugin itself in `project/project/plugins.sbt`:

```bash
sbt> updateSbtPlugin
```

Wrapper plugins can override which plugin gets updated by setting these keys in their own `buildSettings`:

```scala
sbtDependenciesPluginOrganization := "com.example"
sbtDependenciesPluginName         := "sbt-my-plugin"
```

---

</details>

<details><summary><b id="update-everything">Update everything at once</b></summary><br/>

Use `updateAllDependencies` to update the plugin itself, Scala versions, dependencies, scalafmt version, and the SBT version all at once:

```bash
sbt> updateAllDependencies
```

---

</details>

<details><summary><b id="configure-artifact-migrations">Configure artifact migrations</b></summary><br/>

When running `updateDependencies`, the plugin automatically detects when a dependency has moved to new coordinates (new groupId or artifactId) and migrates it. This follows the same [artifact migrations scheme as Scala Steward](https://github.com/scala-steward-org/scala-steward/blob/main/docs/artifact-migrations.md) and uses [Scala Steward's artifact migration list](https://github.com/scala-steward-org/scala-steward/blob/main/modules/core/src/main/resources/artifact-migrations.v2.conf) by default.

Migrated dependencies are shown with a `🔀` indicator:

```
 ↳ 🔀 org.json4s::json4s-core:4.0.7 -> io.github.json4s::json4s-core:4.1.0
```

You can customize the migration sources using the `dependencyMigrations` setting:

```scala
// Disable all migrations
ThisBuild / dependencyMigrations := Nil

// Add a custom migrations URL
ThisBuild / dependencyMigrations += url("https://example.com/my-migrations.conf")

// Use a local file
ThisBuild / dependencyMigrations := List(file("project/artifact-migrations.conf").toURI.toURL)
```

Custom migration files use Scala Steward's HOCON format:

```hocon
changes = [
  {
    groupIdBefore = org.json4s
    groupIdAfter = io.github.json4s
    artifactIdAfter = json4s-core
  }
]
```

Each entry supports `groupIdBefore`, `groupIdAfter`, `artifactIdBefore`, and `artifactIdAfter`. At least one of `groupIdBefore` or `artifactIdBefore` must be defined.

---

</details>

<details><summary><b id="show-library-dependencies">Show library dependencies</b></summary><br/>

Use `showLibraryDependencies` to display the library dependencies for the current project in a formatted, colored output. It shows both direct dependencies and those inherited from dependent projects (via `.dependsOn`).

```bash
sbt> showLibraryDependencies
```

- Green = direct dependencies
- Yellow = inherited from other projects

---

</details>

<details><summary><b id="get-all-resolved-dependencies">Get all resolved dependencies</b></summary><br/>

Use `allProjectDependencies` to get the complete list of resolved library dependencies for the project after conflict resolution and eviction:

```bash
sbt> show allProjectDependencies
```

This is useful for programmatic access to dependencies in custom tasks or checks.

---

</details>

<details><summary><b id="validate-resolved-dependencies">Validate resolved dependencies</b></summary><br/>

Use `dependenciesCheck` to register custom check functions that validate resolved dependencies after `update`. If any check throws, the build fails.

```scala
// build.sbt
dependenciesCheck += { (deps: List[ModuleID]) =>
  if (deps.exists(_.name.contains("log4j")))
    throw new MessageOnlyException("log4j is banned - use logback instead")
}
```

Each function receives the full list of resolved `ModuleID`s after conflict resolution and eviction.

---

</details>

<details><summary><b id="disable-eviction-warnings">Disable eviction warnings</b></summary><br/>

Use `disableEvictionWarnings` to downgrade eviction errors to info level, preventing them from failing the build:

```bash
sbt> disableEvictionWarnings
```

To restore eviction warnings to error level (default behavior), use `enableEvictionWarnings`:

```bash
sbt> enableEvictionWarnings
```

---

</details>

## Contributors to this project

| <a href="https://github.com/alejandrohdezma"><img alt="alejandrohdezma" src="https://avatars.githubusercontent.com/u/9027541?v=4&s=120" width="120px" /></a> |
| :--: |
| <a href="https://github.com/alejandrohdezma"><sub><b>alejandrohdezma</b></sub></a> |
