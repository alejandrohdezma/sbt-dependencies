# `sbt-dependencies` VS Code / Cursor Extension

Syntax highlighting for [`sbt-dependencies`](https://github.com/alejandrohdezma/sbt-dependencies/blob/main/README.md) configuration files (`dependencies.conf`). Works with both VS Code and Cursor.

<img src="https://github.com/alejandrohdezma/sbt-dependencies/blob/main/vscode-extension/images/demo.webp?raw=true" alt="sbt-dependencies" width="600" />

## Features

- Syntax highlighting for dependency strings (organization, artifact, version, configuration)
- Support for both simple and advanced group formats
- Version marker highlighting (`=`, `^`, `~`)
- Variable reference highlighting (`{{name}}`)
- HOCON comment support (`//`, `#`, `/* */`)
- CodeLens navigation between `build.sbt` and `dependencies.conf`: jump from a project definition to its dependency group and vice versa

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
