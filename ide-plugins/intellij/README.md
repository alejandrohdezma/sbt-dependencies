# `sbt-dependencies` IntelliJ IDEA Plugin

IntelliJ IDEA support for [`sbt-dependencies`](https://github.com/alejandrohdezma/sbt-dependencies/blob/main/README.md) configuration files (`dependencies.conf`).

## Features

- **Syntax highlighting**: groups, setting and object-entry keys, and each part of a dependency line (organization, artifact, version, version markers, `*` BOM versions, `{{variable}}` references, configurations). Colors are customizable under `Settings → Editor → Color Scheme → sbt-dependencies`.
- **Structure view**: groups with their dependencies as navigable children (`⌘F12`).
- **Formatting**: `Reformat Code` produces the exact output of the `dependenciesFormat` sbt task — groups and dependencies sorted, canonical indentation, comments dropped. Documents that don't parse are left untouched.
- Comment/uncomment actions and brace matching.

## Installation

The plugin is distributed manually for now:

1. Build the zip from the repository root: `sbt intellij-plugin/packageArtifactZip` (written to `ide-plugins/intellij/target/`).
2. In IDEA: `Settings → Plugins → ⚙ → Install Plugin from Disk...` and pick the zip.

Requires IntelliJ IDEA 2025.1 or newer.

## Development

The plugin is a Scala 3 project of the main sbt build (`intellij-plugin`), built with [sbt-idea-plugin](https://github.com/JetBrains/sbt-idea-plugin) and sharing the [`sbt-dependencies-core`](../../modules/sbt-dependencies-core) sources. The IntelliJ SDK is downloaded on first build load (cached under `~/.sbt-dependenciesPluginIC`).

- `sbt intellij-plugin/test` runs the test suites.
- `sbt intellij-plugin/runIDE` launches a sandboxed IDEA with the plugin installed (open [`ide-plugins/vscode/example`](../vscode/example) to try every construct).
- If the sandbox behaves as if your latest changes are missing (stale classes or resources), delete `ide-plugins/intellij/target/plugin` and relaunch.
