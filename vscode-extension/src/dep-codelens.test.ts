import { describe, it, expect } from "vitest";
import { parsePinnedWithoutNote } from "./dep-codelens";

describe("parsePinnedWithoutNote", () => {
  it("returns pinned dep with = marker in simple group", () => {
    const lines = [
      'my-group = [',
      '  "org.typelevel::cats-core:=2.10.0"',
      ']',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(1);
    expect(results[0].line).toBe(1);
    expect(results[0].org).toBe("org.typelevel");
    expect(results[0].artifact).toBe("cats-core");
    expect(results[0].version).toBe("=2.10.0");
  });

  it("returns pinned dep with ^ marker", () => {
    const lines = [
      'my-group = [',
      '  "org.typelevel::cats-core:^2.10.0"',
      ']',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(1);
    expect(results[0].version).toBe("^2.10.0");
  });

  it("returns pinned dep with ~ marker", () => {
    const lines = [
      'my-group = [',
      '  "org.http4s::http4s-core:~0.23.25"',
      ']',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(1);
    expect(results[0].version).toBe("~0.23.25");
  });

  it("skips deps without version markers", () => {
    const lines = [
      'my-group = [',
      '  "org.typelevel::cats-core:2.10.0"',
      '  "co.fs2::fs2-core:3.9.4"',
      ']',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(0);
  });

  it("skips single-line object entries with notes", () => {
    const lines = [
      'my-group = [',
      '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "v3 drops Scala 2.12" }',
      ']',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(0);
  });

  it("skips single-line object entries without notes (incomplete syntax)", () => {
    const lines = [
      'my-group = [',
      '  { dependency = "org.typelevel::cats-effect:=3.5.4"}',
      ']',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(0);
  });

  it("returns only pinned deps without notes from mixed entries", () => {
    const lines = [
      'my-group = [',
      '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "v3 drops Scala 2.12" }',
      '  "co.fs2::fs2-core:^3.9.4"',
      '  "org.http4s::http4s-core:0.23.25"',
      ']',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(1);
    expect(results[0].org).toBe("co.fs2");
    expect(results[0].artifact).toBe("fs2-core");
  });

  it("works with advanced block dependencies array", () => {
    const lines = [
      'my-project {',
      '  scala-versions = ["2.13.12"]',
      '  dependencies = [',
      '    "org.typelevel::cats-core:^2.10.0"',
      '    "co.fs2::fs2-core:3.9.4"',
      '  ]',
      '}',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(1);
    expect(results[0].line).toBe(3);
    expect(results[0].org).toBe("org.typelevel");
  });

  it("ignores deps outside of arrays", () => {
    const lines = [
      '// "org.typelevel::cats-core:^2.10.0"',
      'my-group = [',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(1);
    expect(results[0].org).toBe("co.fs2");
  });

  it("returns multiple pinned deps across groups", () => {
    const lines = [
      'group-a = [',
      '  "org.typelevel::cats-core:^2.10.0"',
      ']',
      '',
      'group-b = [',
      '  "co.fs2::fs2-core:=3.9.4"',
      ']',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(2);
    expect(results[0].org).toBe("org.typelevel");
    expect(results[1].org).toBe("co.fs2");
  });

  it("returns empty for no pinned deps", () => {
    const lines = [
      'my-group = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(0);
  });

  it("handles comment lines inside arrays", () => {
    const lines = [
      'my-group = [',
      '  // pinned for compat',
      '  "org.typelevel::cats-core:^2.10.0"',
      ']',
    ];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(1);
    expect(results[0].line).toBe(2);
  });

  it("handles empty arrays", () => {
    const lines = ['my-group = []'];
    const results = parsePinnedWithoutNote(lines);
    expect(results).toHaveLength(0);
  });
});
