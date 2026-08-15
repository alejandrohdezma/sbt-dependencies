# sbt-dependencies-core

The sbt-free heart of sbt-dependencies: everything needed to understand `dependencies.conf` files without an sbt
classpath, shared by the sbt plugin and the IDE plugins under `ide-plugins/`.

## The three layers

There are deliberately **three** ways to read a `dependencies.conf`, each with different guarantees. When adding a
feature, pick the layer that matches — don't add a fourth reader.

| Layer | Package | Guarantees | Use it for |
| :--- | :--- | :--- | :--- |
| **Positioned syntax** — `DependenciesDocument`, `Diagnostics` | `document` | Lenient: never fails, best-effort on half-typed text, absolute character offsets for every element. No semantics. | Editor features: structure views, diagnostics/annotations, hovers, anything that must point back into the text. |
| **Canonical model** — `GroupConfig`, `AnnotatedDependency`, `Dependency`, `Group` | `io`, `model` | Strict: HOCON-exact parsing (typesafe-config), refuses documents it doesn't fully understand, drops all positions. Round-trips: `parseAll` reads, `render`/`format` write the canonical form. | The formatter (sbt and IDEs must produce byte-identical output) and any semantic validation (`Dependency.parse`, `validateBomRestrictions` return `Either`). |
| **sbt seam** — `DependenciesFile`, `DependencyOps` | sbt plugin module (`modules/sbt-dependencies`) | Full resolution: disk I/O, variable resolvers, BOM pins, `ModuleID` conversion, logging/failing through sbt. | sbt tasks and commands only. |

Rules of thumb:

- Positions live only in `document`. The canonical model is value-compared everywhere and is constructed in places
  where no text exists, so it must never carry positions.
- Error messages users see in editors should come from the canonical model's `Either`-returning validators, so IDE
  diagnostics and sbt failures never disagree.
- Anything importing `sbt.*` belongs in the sbt plugin module, behind `DependencyOps`/`DependenciesFile`.
- The vocabulary of the file format (field and setting names) lives in `model.Fields` — every layer shares it.
