# `sbt-dependencies` VS Code / Cursor Extension

Syntax highlighting for [`sbt-dependencies`](https://github.com/alejandrohdezma/sbt-dependencies/blob/main/README.md) configuration files (`dependencies.conf`). Works with both VS Code and Cursor.

<img src="https://github.com/alejandrohdezma/sbt-dependencies/blob/main/vscode-extension/images/demo.webp?raw=true" alt="sbt-dependencies" width="600" />

## Features

- Syntax highlighting for dependency strings (organization, artifact, version, configuration including `:sbt-plugin` and `:compiler-plugin`)
- Support for both simple and advanced group formats
- Syntax highlighting for advanced group settings (`scala-version`, `scala-versions`, `java-version`)
- Support for object format with notes (`{ dependency = "...", note = "..." }`) and the `cross-version` annotation for compiler plugins
- Version marker highlighting (`=`, `^`, `~`) and BOM-managed `*` versions, validated the way the plugin does (`*` cannot be combined with the `bom`/`sbt-plugin` configuration or a `full`/`patch` cross-version)
- Variable reference highlighting (`{{name}}`)
- Resolved versions shown inline for `*` and `{{variable}}` dependencies, with the pinning BOM (or variable) revealed on hover — read from the `target/sbt-dependencies/.sbt-resolutions` file the plugin writes on load (requires a plugin version that emits it), and refreshed on each sbt reload. A `(stale)` marker appears when the buffer has been edited since the last reload
- Quick-fixes to switch a hardcoded version a BOM manages to `*`, and to materialize a `*` back into its resolved version
- HOCON comment support (`//`, `#`, `/* */`)
- CodeLens navigation between `build.sbt` and `dependencies.conf`: jump from a project definition to its dependency group and vice versa
- CodeLens hint on pinned dependencies without a note, with a quick action to add one

## Development

Press **F5** in VS Code or Cursor to launch a development Extension Host with the extension loaded.

## Packaging

```bash
npm run package
```

This creates a `.vsix` file that can be installed with:

```bash
code --install-extension sbt-dependencies-*.vsix
```
