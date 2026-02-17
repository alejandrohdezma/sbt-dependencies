import { describe, it, expect } from "vitest";
import { formatDocument } from "./formatting";

describe("formatDocument", () => {
  it("sorts unsorted deps alphabetically in simple group", () => {
    const lines = [
      'my-group = [',
      '  "org.typelevel::cats-core:2.10.0"',
      '  "co.fs2::fs2-core:^3.9.4"',
      '  "com.typesafe:config:1.4.3"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      '  "com.typesafe:config:1.4.3"',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ].join("\n"));
  });

  it("keeps comment above a dep attached after sorting", () => {
    const lines = [
      'my-group = [',
      '  // Core library',
      '  "org.typelevel::cats-core:2.10.0"',
      '  // FS2 streaming',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  // FS2 streaming',
      '  "co.fs2::fs2-core:^3.9.4"',
      '  // Core library',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ].join("\n"));
  });

  it("preserves group order", () => {
    const lines = [
      'group-b = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
      '',
      'group-a = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'group-b = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
      '',
      'group-a = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ].join("\n"));
  });

  it("preserves non-dependency fields in advanced blocks", () => {
    const lines = [
      'my-project {',
      '  scala-versions = ["2.13.12", "3.3.3"]',
      '  dependencies = [',
      '    "org.typelevel::cats-core:2.10.0"',
      '    "co.fs2::fs2-core:^3.9.4"',
      '  ]',
      '}',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-project {',
      '  scala-versions = ["2.13.12", "3.3.3"]',
      '  dependencies = [',
      '    "co.fs2::fs2-core:^3.9.4"',
      '    "org.typelevel::cats-core:2.10.0"',
      '  ]',
      '}',
    ].join("\n"));
  });

  it("normalizes indentation", () => {
    const lines = [
      'my-group = [',
      '      "org.typelevel::cats-core:2.10.0"',
      '   "co.fs2::fs2-core:^3.9.4"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ].join("\n"));
  });

  it("leaves empty groups unchanged", () => {
    const lines = [
      'my-group = []',
    ];
    const result = formatDocument(lines);
    expect(result).toBe('my-group = []');
  });

  it("keeps trailing comments at end of group", () => {
    const lines = [
      'my-group = [',
      '  "org.typelevel::cats-core:2.10.0"',
      '  // TODO: add more deps',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "org.typelevel::cats-core:2.10.0"',
      '  // TODO: add more deps',
      ']',
    ].join("\n"));
  });

  it("does not change already sorted deps", () => {
    const lines = [
      'my-group = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      '  "com.typesafe:config:1.4.3"',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe(lines.join("\n"));
  });

  it("formats real-world example correctly", () => {
    const lines = [
      '// SBT build plugins',
      'sbt-build = [',
      '  "org.scalameta:sbt-scalafmt:2.5.6:sbt-plugin"',
      '  "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"',
      '  "com.github.sbt:sbt-ci-release:1.11.2:sbt-plugin"',
      '  "com.alejandrohdezma:sbt-ci:2.22.0:sbt-plugin"',
      ']',
      '',
      '',
      'my-project {',
      '  scala-versions = ["~2.13.12", "^3.3.3"]',
      '  dependencies = [',
      '    "org.typelevel::cats-core:^2.10.0"',
      '    "co.fs2::fs2-core:^3.9.4"',
      '    "org.http4s::http4s-core:~0.23.25"',
      '    "org.scalameta::munit:1.0.0:test"',
      '    "org.typelevel::munit-cats-effect:2.0.0:test"',
      '    "com.disneystreaming.smithy4s::smithy4s-core:{{smithy4sVersion}}"',
      '  ]',
      '}',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      '// SBT build plugins',
      'sbt-build = [',
      '  "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"',
      '  "com.alejandrohdezma:sbt-ci:2.22.0:sbt-plugin"',
      '  "com.github.sbt:sbt-ci-release:1.11.2:sbt-plugin"',
      '  "org.scalameta:sbt-scalafmt:2.5.6:sbt-plugin"',
      ']',
      '',
      'my-project {',
      '  scala-versions = ["~2.13.12", "^3.3.3"]',
      '  dependencies = [',
      '    "co.fs2::fs2-core:^3.9.4"',
      '    "com.disneystreaming.smithy4s::smithy4s-core:{{smithy4sVersion}}"',
      '    "org.http4s::http4s-core:~0.23.25"',
      '    "org.scalameta::munit:1.0.0:test"',
      '    "org.typelevel::cats-core:^2.10.0"',
      '    "org.typelevel::munit-cats-effect:2.0.0:test"',
      '  ]',
      '}',
    ].join("\n"));
  });

  it("normalizes multiple blank lines between groups to one", () => {
    const lines = [
      'group-a = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
      '',
      '',
      '',
      'group-b = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'group-a = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
      '',
      'group-b = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ].join("\n"));
  });

  it("adds blank line between groups when missing", () => {
    const lines = [
      'group-a = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
      'group-b = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'group-a = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
      '',
      'group-b = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ].join("\n"));
  });

  it("preserves comment between groups with one blank line", () => {
    const lines = [
      'group-a = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
      '',
      '',
      '// Shared utilities',
      '',
      'group-b = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'group-a = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
      '',
      '// Shared utilities',
      'group-b = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ].join("\n"));
  });

  it("does not add blank line before first group", () => {
    const lines = [
      'group-a = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'group-a = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ].join("\n"));
  });

  it("handles hash comments in groups", () => {
    const lines = [
      'my-group = [',
      '  # Core',
      '  "org.typelevel::cats-core:2.10.0"',
      '  # Testing',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  # Testing',
      '  "co.fs2::fs2-core:^3.9.4"',
      '  # Core',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ].join("\n"));
  });
});
