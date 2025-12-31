Manage SBT dependencies from a single YAML file with version markers, auto-updates, and cross-project support

## Installation

Add the following line to your `project/project/plugins.sbt` file:

```sbt
addSbtPlugin("com.alejandrohdezma" % "sbt-dependencies" % "0.2.0")
```

> Adding the plugin to `project/project/plugins.sbt` (meta-build) allows it to 
> manage both your build dependencies and your project dependencies.

## Usage

### The `dependencies.yaml` file

Create a `project/dependencies.yaml` file listing your dependencies (or run `initDependenciesFile` to generate it from your existing `libraryDependencies`):

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

## Commands & Tasks

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

Updates everything: the plugin itself, build dependencies, and project dependencies.

```bash
sbt> updateAllDependencies
```

### `updateSbtDependenciesPlugin`

Updates the `sbt-dependencies` plugin itself in `project/project/plugins.sbt`.

```bash
sbt> updateSbtDependenciesPlugin
```

### `initDependenciesFile`

Creates (or recreates) the `project/dependencies.yaml` file based on your current `libraryDependencies`. This is useful when first adopting the plugin or when you want to migrate existing dependencies from `build.sbt` to the YAML format.

```bash
sbt> initDependenciesFile
```

The command will:
- Read all `libraryDependencies` from your projects
- Create groups for each project (using the project name)
- Also populate the `sbt-build` group with your SBT plugins
- Preserve any existing dependencies in groups that aren't being updated

## Contributors to this project

| <a href="https://github.com/alejandrohdezma"><img alt="alejandrohdezma" src="https://avatars.githubusercontent.com/u/9027541?v=4&s=120" width="120px" /></a> |
| :--: |
| <a href="https://github.com/alejandrohdezma"><sub><b>alejandrohdezma</b></sub></a> |
