Manage SBT dependencies from a single YAML file with version markers, auto-updates, and cross-project support

## Installation

Add the following line to your `project/project/plugins.sbt` file:

```sbt
addSbtPlugin("com.alejandrohdezma" % "sbt-dependencies" % "0.7.0")
```

> Adding the plugin to `project/project/plugins.sbt` (meta-build) allows it to 
> manage both your build dependencies and your project dependencies.

## Usage

### The `dependencies.yaml` file

Create a `project/dependencies.yaml` file listing your dependencies:

```yaml
sbt-build:
  - ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin
  - org.scalameta:sbt-scalafmt:2.5.4:sbt-plugin
  - io.get-coursier::coursier:2.1.24

my-project:
  - org.typelevel::cats-core:2.10.0
  - org.scalameta::munit:1.2.1:test
```

Groups correspond to:
- `sbt-build`: Dependencies for your build definition (plugins and libraries used in `build.sbt`)
- `<project-name>`: Dependencies for a specific project (matches the SBT project name)

The plugin automatically populates `libraryDependencies` for each project based on its group.

> **Tip:** Run `initDependenciesFile` to automatically generate this file from your existing `libraryDependencies` and `addSbtPlugin` settings.

### Dependency format

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

### Scala version filtering

Dependencies with Scala version suffixes in their artifact name are automatically filtered based on the current `scalaVersion`:

```yaml
my-project:
  - org.example:my-lib_2.13:1.0.0  # Only added when scalaVersion is 2.13.x
  - org.example:my-lib_2.12:1.0.0  # Only added when scalaVersion is 2.12.x
  - org.example:my-lib_3:1.0.0     # Only added when scalaVersion is 3.x
  - org.example:other-lib:1.0.0    # Always added (no suffix)
```

This is useful for dependencies that are published with Scala-specific variants but aren't cross-compiled in the usual way (e.g., some native libraries or Java libraries with Scala-specific modules).

### Version markers

Control how dependencies are updated using version markers:

| Marker | Example | Behavior |
|--------|---------|----------|
| (none) | `2.10.0` | Update to latest compatible version |
| `=` | `=2.10.0` | Pin to exact version, never update |
| `^` | `^2.10.0` | Update within major version only (2.x.x) |
| `~` | `~2.10.0` | Update within minor version only (2.10.x) |

### Variable versions

You can use variable syntax to reference versions defined (or computed) in your build:

```yaml
my-project:
  - org.typelevel::cats-core:{{catsVersion}}
  - org.typelevel::cats-effect:{{catsVersion}}
```

Define variable resolvers in your `build.sbt`:

```scala
dependencyVersionVariables := Map(
  "catsVersion" -> { artifact => artifact % "2.10.0" }
)
```

When running `updateDependencies`, variable-based dependencies show their resolved version and the latest available version, but the variable reference is preserved in the YAML file.

### Advanced format

Groups support an advanced format that enables additional configuration beyond just listing dependencies:

```yaml
my-project:
  scala-versions:
    - 2.13.12
    - 2.12.18
    - 3.3.1
  dependencies:
    - org.typelevel::cats-core:2.10.0
    - org.scalameta::munit:1.2.1:test
```

The simple format (array of dependencies) and advanced format (object with `dependencies` key) can be mixed in the same file.

### Scala versions

You can configure `scalaVersion` and `crossScalaVersions` directly in `dependencies.yaml` using the advanced format:

```yaml
sbt-build:
  scala-versions:
    - 2.13.12
    - 2.12.18
  dependencies:
    - ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin

my-project:
  scala-version: 3.3.1
  dependencies:
    - org.typelevel::cats-core:2.10.0
```

Use `scala-version` (singular) for a single version or `scala-versions` (plural) for cross-building.

**Behavior:**
- The first version becomes `scalaVersion`
- All versions become `crossScalaVersions`
- `scala-version`/`scala-versions` in the `sbt-build` group applies at the build level (`ThisBuild / scalaVersion` and `ThisBuild / crossScalaVersions`)
- `scala-version`/`scala-versions` in individual project groups overrides the build-level settings for that project

This allows you to set a default Scala version for all projects while letting specific projects use different versions.

#### Example: Using with [here-sbt-bom](https://github.com/heremaps/here-sbt-bom)

The `here-sbt-bom` plugin reads Maven BOM files and exposes version constants. You can reference these in your `dependencies.yaml`:

```yaml
my-project:
  - com.fasterxml.jackson.core:jackson-core:{{jackson}}
  - com.fasterxml.jackson.core:jackson-databind:{{jackson}}
```

```scala
// build.sbt
val jacksonBom = Bom("com.fasterxml.jackson" % "jackson-bom" % "2.14.2")

dependencyVersionVariables := Map(
  "jackson" -> { artifact => artifact % jacksonBom.key.value }
)
```

## Commands & Tasks

### `initDependenciesFile`

Creates (or recreates) the `dependencies.yaml` file based on your current `libraryDependencies` and `addSbtPlugin` settings. This is useful when migrating an existing project to use this plugin.

```bash
sbt> initDependenciesFile
```

After running this command, remember to remove the `libraryDependencies +=` and `addSbtPlugin` lines from your build files, as the plugin will now manage them via `dependencies.yaml`.

### `showLibraryDependencies`

Displays the library dependencies for the current project in a formatted, colored output. Shows both direct dependencies and those inherited from dependent projects (via `.dependsOn`).

```bash
sbt> showLibraryDependencies
```

- Green = direct dependencies
- Yellow = inherited from other projects

### `updateDependencies [filter]`

Updates dependencies in the current project to their latest versions.

```bash
sbt> updateDependencies # Update all project dependencies
sbt> updateDependencies org.typelevel: # Update all `org.typelevel` dependencies
sbt> updateDependencies :cats-core # Update `cats-core` from any organization
sbt> updateDependencies org.typelevel:cats-core # Update specific dependency
```

### `install <dependency>`

Installs a new dependency to the current project.

```bash
sbt> install org.typelevel::cats-core:2.10.0
sbt> install org.typelevel::cats-effect # Resolves latest version
sbt> install org.scalameta::munit:1.2.1:test
```

### `updateBuildDependencies`

Updates dependencies in the meta-build (`project/dependencies.yaml`, group `sbt-build`).

```bash
sbt> updateBuildDependencies
```

### `installBuildDependencies <dependency>`

Installs a new dependency to the meta-build.

```bash
sbt> installBuildDependencies ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin
```

### `updateAllDependencies`

Updates everything: the plugin itself, Scala versions, dependencies, and SBT version.

```bash
sbt> updateAllDependencies
```

### `updateSbtDependenciesPlugin`

Updates the `sbt-dependencies` plugin itself in `project/project/plugins.sbt`.

```bash
sbt> updateSbtDependenciesPlugin
```

### `updateSbt`

Updates the SBT version in `project/build.properties` to the latest version. If updated, triggers a reboot to apply the new version.

```bash
sbt> updateSbt
```

### `updateScalaVersions`

Updates Scala versions in the current project to their latest versions within the same minor line.

```bash
sbt> updateScalaVersions
```

Each version is updated within its minor line:
- `2.13.12` → latest `2.13.x`
- `2.12.18` → latest `2.12.x`
- `3.3.1` → latest `3.3.x`

### `updateBuildScalaVersions`

Updates Scala versions in the `sbt-build` group (build-level settings).

```bash
sbt> updateBuildScalaVersions
```

## Contributors to this project

| <a href="https://github.com/alejandrohdezma"><img alt="alejandrohdezma" src="https://avatars.githubusercontent.com/u/9027541?v=4&s=120" width="120px" /></a> |
| :--: |
| <a href="https://github.com/alejandrohdezma"><sub><b>alejandrohdezma</b></sub></a> |
