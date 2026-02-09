# `sbt-dependencies` VS Code / Cursor Extension

Syntax highlighting for [`sbt-dependencies`](../README.md) configuration files (`dependencies.conf`). Works with both VS Code and Cursor.

![](https://github.com/user-attachments/assets/f7144c2b-3171-40a8-9556-6aca28de7de2)

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
