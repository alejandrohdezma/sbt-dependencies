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

  it("strips comments above deps", () => {
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
      '  "co.fs2::fs2-core:^3.9.4"',
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

  it("strips trailing comments at end of group", () => {
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
      '    "org.typelevel::cats-core:^2.10.0"',
      '    "org.scalameta::munit:1.0.0:test"',
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

  it("strips comment between groups", () => {
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

  it("sorts by config first (empty before named) then by org:artifact", () => {
    const lines = [
      'my-group = [',
      '  "org.scalameta::munit:1.0.0:test"',
      '  "org.typelevel::cats-core:^2.10.0"',
      '  "co.fs2::fs2-core:^3.9.4"',
      '  "org.typelevel::munit-cats-effect:2.0.0:test"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      '  "org.typelevel::cats-core:^2.10.0"',
      '  "org.scalameta::munit:1.0.0:test"',
      '  "org.typelevel::munit-cats-effect:2.0.0:test"',
      ']',
    ].join("\n"));
  });

  it("sorts single-line object entries alongside string entries", () => {
    const lines = [
      'my-group = [',
      '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "v3 drops Scala 2.12" }',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "v3 drops Scala 2.12" }',
      ']',
    ].join("\n"));
  });

  it("preserves object entries in advanced block dependencies", () => {
    const lines = [
      'my-project {',
      '  scala-versions = ["2.13.12"]',
      '  dependencies = [',
      '    { dependency = "org.typelevel::cats-core:=2.10.0", note = "Exact pin" }',
      '    "co.fs2::fs2-core:^3.9.4"',
      '  ]',
      '}',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-project {',
      '  scala-versions = ["2.13.12"]',
      '  dependencies = [',
      '    "co.fs2::fs2-core:^3.9.4"',
      '    { dependency = "org.typelevel::cats-core:=2.10.0", note = "Exact pin" }',
      '  ]',
      '}',
    ].join("\n"));
  });

  it("handles multi-line object entries", () => {
    const lines = [
      'my-group = [',
      '  {',
      '    dependency = "org.typelevel::cats-core:^2.10.0"',
      '    note = "v3 drops Scala 2.12"',
      '  }',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "v3 drops Scala 2.12" }',
      ']',
    ].join("\n"));
  });

  it("strips hash comments in groups", () => {
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
      '  "co.fs2::fs2-core:^3.9.4"',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ].join("\n"));
  });

  it("converts SBT %% dependency to HOCON format", () => {
    const lines = [
      'my-group = [',
      '  "org.http4s" %% "http4s-dsl" % "0.23.33"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "org.http4s::http4s-dsl:0.23.33"',
      ']',
    ].join("\n"));
  });

  it("converts SBT % dependency to HOCON format", () => {
    const lines = [
      'my-group = [',
      '  "org.http4s" % "http4s-netty-client_2.13" % "0.5.28"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "org.http4s:http4s-netty-client_2.13:0.5.28"',
      ']',
    ].join("\n"));
  });

  it("converts SBT dependency with config", () => {
    const lines = [
      'my-group = [',
      '  "org.scalameta" %% "munit" % "1.0.0" % "test"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "org.scalameta::munit:1.0.0:test"',
      ']',
    ].join("\n"));
  });

  it("strips libraryDependencies prefix from SBT dependency", () => {
    const lines = [
      'my-group = [',
      '  libraryDependencies += "org.http4s" %% "http4s-dsl" % "0.23.33"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "org.http4s::http4s-dsl:0.23.33"',
      ']',
    ].join("\n"));
  });

  it("converts and sorts SBT deps alongside normal deps", () => {
    const lines = [
      'my-group = [',
      '  "org.typelevel::cats-core:2.10.0"',
      '  "co.fs2" %% "fs2-core" % "3.9.4"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "co.fs2::fs2-core:3.9.4"',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ].join("\n"));
  });

  it("preserves intransitive = true in single-line object", () => {
    const lines = [
      'my-group = [',
      '  { dependency = "org.http4s::http4s-core:=0.23.3", intransitive = true }',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  { dependency = "org.http4s::http4s-core:=0.23.3", intransitive = true }',
      ']',
    ].join("\n"));
  });

  it("preserves both note and intransitive = true", () => {
    const lines = [
      'my-group = [',
      '  { dependency = "org.http4s::http4s-core:=0.23.3", note = "reason", intransitive = true }',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  { dependency = "org.http4s::http4s-core:=0.23.3", note = "reason", intransitive = true }',
      ']',
    ].join("\n"));
  });

  it("normalizes multi-line intransitive object to single-line when short enough", () => {
    const lines = [
      'my-group = [',
      '  {',
      '    dependency = "org.http4s::http4s-core:=0.23.3"',
      '    intransitive = true',
      '  }',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  { dependency = "org.http4s::http4s-core:=0.23.3", intransitive = true }',
      ']',
    ].join("\n"));
  });

  it("sorts intransitive object entry alongside other entries", () => {
    const lines = [
      'my-group = [',
      '  { dependency = "org.typelevel::cats-core:=2.10.0", intransitive = true }',
      '  "co.fs2::fs2-core:3.9.4"',
      ']',
    ];
    const result = formatDocument(lines);
    expect(result).toBe([
      'my-group = [',
      '  "co.fs2::fs2-core:3.9.4"',
      '  { dependency = "org.typelevel::cats-core:=2.10.0", intransitive = true }',
      ']',
    ].join("\n"));
  });
});
