# `sbt-dependencies` IntelliJ IDEA Plugin

IntelliJ IDEA support for [`sbt-dependencies`](https://github.com/alejandrohdezma/sbt-dependencies/blob/main/README.md) configuration files (`dependencies.conf`).

## Features

- **Syntax highlighting**: groups, setting and object-entry keys, and each part of a dependency line (organization, artifact, version, version markers, `*` BOM versions, `{{variable}}` references, configurations). Colors are customizable under `Settings → Editor → Color Scheme → sbt-dependencies`.
- **Diagnostics**: malformed dependencies, invalid `*` BOM usages, invalid `cross-version` values, incomplete object entries and unclosed `{{variable}}` references as errors; duplicate dependencies as warnings. Messages match the errors the sbt plugin itself would fail with, and duplicate or empty entries offer a `Remove dependency entry` quick fix.
- **Structure view**: groups with their dependencies as navigable children (`⌘F12`).
- **Formatting**: `Reformat Code` produces the exact output of the `dependenciesFormat` sbt task — groups and dependencies sorted, canonical indentation, comments dropped. Documents that don't parse are left untouched.
- **Hovers**: dependencies show organization, artifact, version with its update-policy explanation, configuration and a link to mvnrepository.com (`Shift+F1` opens it directly); the reserved `sbt-build` and `common-settings` group names document themselves.
- **Resolved versions**: `*` and `{{variable}}` dependencies show their concrete version as ghost text (with a `(stale)` marker after edits), read from the `.sbt-resolutions` dump the sbt plugin writes on load; hovers reveal the pinning BOM or variable. `Alt+Enter` materializes a `*` into its resolved version, and hardcoded versions a BOM manages are flagged with a rewrite to `*`.
- **Note hints**: pinned or intransitive entries without a `note` are flagged with an `Add note` intention; single-line object entries fold into `"dependency" // note`, expanding while the caret is inside them.
- **Paste conversion**: SBT-style dependencies (`libraryDependencies +=`, `addSbtPlugin(...)`) paste as canonical strings.
- **Navigation**: `⌘`-click jumps between a group name and its `lazy val` project definition in `build.sbt` (both directions); the caret highlights every usage of a `{{variable}}` or dependency coordinate, and variables offer a rename intention.
- **sbt tasks**: `Tools → sbt-dependencies` runs `updateAllDependencies`, `updateDependencies` and the per-group install tasks through `sbtn`, with output in the Run tool window.
- Comment/uncomment actions and brace matching.

## Installation

Install it from the [JetBrains Marketplace](https://plugins.jetbrains.com/search?search=sbt-dependencies): in IDEA, go to `Settings → Plugins → Marketplace` and search for "sbt-dependencies". New versions are published automatically on every release of this repository.

Alternatively, build the zip from source:

1. From the repository root: `sbt intellij-plugin/packageArtifactZip` (written to `ide-plugins/intellij/target/`).
2. In IDEA: `Settings → Plugins → ⚙ → Install Plugin from Disk...` and pick the zip.

Requires IntelliJ IDEA 2025.1 or newer.

## Development

The plugin is a Scala 3 project of the main sbt build (`intellij-plugin`), built with [sbt-idea-plugin](https://github.com/JetBrains/sbt-idea-plugin) and sharing the [`sbt-dependencies-core`](../../modules/sbt-dependencies-core) sources. The IntelliJ SDK is downloaded on first build load (cached under `~/.sbt-dependenciesPluginIC`).

- `sbt intellij-plugin/test` runs the test suites.
- `sbt intellij-plugin/runIDE` launches a sandboxed IDEA with the plugin installed (open [`ide-plugins/vscode/example`](../vscode/example) to try every construct).
- If the sandbox behaves as if your latest changes are missing (stale classes or resources), delete `ide-plugins/intellij/target/plugin` and relaunch.
