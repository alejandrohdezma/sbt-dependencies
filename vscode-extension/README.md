# `sbt-dependencies` VS Code / Cursor Extension

Syntax highlighting for [`sbt-dependencies`](../README.md) configuration files (`dependencies.conf`). Works with both VS Code and Cursor.

![](https://github.com/user-attachments/assets/5df762db-956d-4bd5-8583-d8b25877b5dc)

## Features

- Syntax highlighting for dependency strings (organization, artifact, version, configuration)
- Support for both simple and advanced group formats
- Version marker highlighting (`=`, `^`, `~`)
- Variable reference highlighting (`{{name}}`)
- HOCON comment support (`//`, `#`, `/* */`)

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
