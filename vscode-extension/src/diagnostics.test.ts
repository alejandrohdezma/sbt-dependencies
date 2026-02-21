import { describe, it, expect } from "vitest";
import { validateDependencyString, parseDiagnostics } from "./diagnostics";

describe("validateDependencyString", () => {
  describe("valid inputs", () => {
    it.each([
      ["org.typelevel::cats-core:2.10.0", "standard Scala dep"],
      ["com.typesafe:config:1.4.3", "Java dep"],
      ["io.circe::circe-core:=0.14.6", "= marker"],
      ["org.typelevel::cats-core:^2.10.0", "^ marker"],
      ["org.http4s::http4s-core:~0.23.25", "~ marker"],
      ['com.disneystreaming.smithy4s::smithy4s-core:{{smithy4sVersion}}', "variable version"],
      ["org.scalameta::munit:1.0.0:test", "with configuration"],
      ["ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin", "sbt plugin"],
    ])("%s (%s)", (input) => {
      expect(validateDependencyString(input, 0, 0)).toBeUndefined();
    });
  });

  describe("invalid inputs", () => {
    it('returns "Empty dependency string" for empty input', () => {
      const result = validateDependencyString("", 0, 0);
      expect(result).toBeDefined();
      expect(result!.message).toBe("Empty dependency string");
    });

    it('returns unclosed variable reference error', () => {
      const result = validateDependencyString("org::art:{{broken", 0, 0);
      expect(result).toBeDefined();
      expect(result!.message).toBe('Unclosed variable reference: missing "}}"');
    });

    it('returns malformed dependency error for single word', () => {
      const result = validateDependencyString("just-a-word", 0, 0);
      expect(result).toBeDefined();
      expect(result!.message).toBe('Malformed dependency: expected format "org:artifact" or "org::artifact"');
    });

    it('returns missing version error', () => {
      const result = validateDependencyString("org.typelevel::cats-core", 0, 0);
      expect(result).toBeDefined();
      expect(result!.message).toBe('Missing version: expected format "org:artifact:version"');
    });

    it('returns invalid version marker error for "!"', () => {
      const result = validateDependencyString("org::art:!2.0", 0, 0);
      expect(result).toBeDefined();
      expect(result!.message).toBe('Invalid version marker "!": expected "=", "^", or "~"');
    });

    it('returns invalid version marker error for "+"', () => {
      const result = validateDependencyString("org::art:+2.0", 0, 0);
      expect(result).toBeDefined();
      expect(result!.message).toBe('Invalid version marker "+": expected "=", "^", or "~"');
    });

    it("sets correct range", () => {
      const result = validateDependencyString("bad", 5, 10);
      expect(result).toBeDefined();
      expect(result!.range).toEqual({ startLine: 5, startCol: 10, endLine: 5, endCol: 13 });
    });

    it("always has severity error and source sbt-dependencies", () => {
      const result = validateDependencyString("", 0, 0);
      expect(result!.severity).toBe("error");
      expect(result!.source).toBe("sbt-dependencies");
    });
  });
});

describe("parseDiagnostics", () => {
  describe("simple groups", () => {
    it("returns no diagnostics for valid deps in simple array", () => {
      const lines = [
        'my-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        '  "com.typesafe:config:1.4.3"',
        ']',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });

    it("returns 1 diagnostic for invalid dep in simple array", () => {
      const lines = [
        'my-group = [',
        '  "just-a-word"',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].message).toBe('Malformed dependency: expected format "org:artifact" or "org::artifact"');
      expect(result[0].range.startLine).toBe(1);
    });

    it("only flags the invalid dep among multiple deps", () => {
      const lines = [
        'my-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        '  "bad"',
        '  "com.typesafe:config:1.4.3"',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].range.startLine).toBe(2);
    });
  });

  describe("advanced blocks", () => {
    it("returns no diagnostics for valid deps in advanced block", () => {
      const lines = [
        'my-group {',
        '  dependencies = [',
        '    "org.typelevel::cats-core:2.10.0"',
        '  ]',
        '}',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });

    it("returns 1 diagnostic for invalid dep in dependencies array", () => {
      const lines = [
        'my-group {',
        '  dependencies = [',
        '    "bad"',
        '  ]',
        '}',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].message).toBe('Malformed dependency: expected format "org:artifact" or "org::artifact"');
    });

    it("does not validate non-dependency arrays in advanced blocks", () => {
      const lines = [
        'my-group {',
        '  scala-versions = ["~2.13.12"]',
        '}',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });
  });

  describe("context isolation", () => {
    it("does not validate strings outside any group", () => {
      const lines = [
        '"just-a-word"',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });

    it("does not validate non-dependency arrays in advanced blocks", () => {
      const lines = [
        'my-group {',
        '  other-field = ["not-a-dep"]',
        '}',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });
  });

  describe("comment handling", () => {
    it("handles block comments spanning multiple lines", () => {
      const lines = [
        '/* my-group = [',
        '  "bad"',
        '] */',
        'real-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });
  });

  describe("duplicate detection", () => {
    it("warns on second occurrence of same org::artifact in same group", () => {
      const lines = [
        'my-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        '  "org.typelevel::cats-core:^2.11.0"',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].severity).toBe("warning");
      expect(result[0].message).toBe("Duplicate dependency in group");
      expect(result[0].range.startLine).toBe(2);
    });

    it("does not warn for same org::artifact in different groups", () => {
      const lines = [
        'group-a = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
        'group-b = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });

    it("treats different separators as distinct (: vs ::)", () => {
      const lines = [
        'my-group = [',
        '  "com.typesafe:config:1.4.3"',
        '  "com.typesafe::config:1.0.0"',
        ']',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });

    it("warns on same org::artifact with different versions", () => {
      const lines = [
        'my-group = [',
        '  "org.typelevel::cats-core:=2.10.0"',
        '  "org.typelevel::cats-core:^2.11.0"',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].severity).toBe("warning");
      expect(result[0].range.startLine).toBe(2);
    });

    it("warns on 2nd and 3rd occurrence when three duplicates exist", () => {
      const lines = [
        'my-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        '  "org.typelevel::cats-core:^2.11.0"',
        '  "org.typelevel::cats-core:~2.12.0"',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(2);
      expect(result[0].severity).toBe("warning");
      expect(result[0].range.startLine).toBe(2);
      expect(result[1].severity).toBe("warning");
      expect(result[1].range.startLine).toBe(3);
    });
  });

  describe("object format entries", () => {
    it("returns no diagnostics for valid single-line object entry", () => {
      const lines = [
        'my-group = [',
        '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "v3 drops Scala 2.12" }',
        ']',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });

    it("returns no diagnostics for mixed string and object entries", () => {
      const lines = [
        'my-group = [',
        '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "v3 drops Scala 2.12" }',
        '  "org.scalameta::munit:1.2.1:test"',
        ']',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });

    it("returns error for object entry without dependency field", () => {
      const lines = [
        'my-group = [',
        '  { note = "missing dep" }',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].message).toBe("Object entry must have a 'dependency' field");
    });

    it("returns error for object entry without note field", () => {
      const lines = [
        'my-group = [',
        '  { dependency = "org.typelevel::cats-core:^2.10.0" }',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].message).toBe("Object entry must have a 'note' field");
    });

    it("validates dependency value inside object entry", () => {
      const lines = [
        'my-group = [',
        '  { dependency = "bad", note = "reason" }',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].message).toBe('Malformed dependency: expected format "org:artifact" or "org::artifact"');
    });

    it("returns no diagnostics for valid object entry in advanced block", () => {
      const lines = [
        'my-group {',
        '  dependencies = [',
        '    { dependency = "org.typelevel::cats-core:^2.10.0", note = "v3 drops Scala 2.12" }',
        '  ]',
        '}',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });

    it("detects duplicates across string and object entries", () => {
      const lines = [
        'my-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        '  { dependency = "org.typelevel::cats-core:^2.11.0", note = "pinned" }',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].severity).toBe("warning");
      expect(result[0].message).toBe("Duplicate dependency in group");
    });

    it("handles multi-line object entry", () => {
      const lines = [
        'my-group = [',
        '  {',
        '    dependency = "org.typelevel::cats-core:^2.10.0"',
        '    note = "v3 drops Scala 2.12"',
        '  }',
        ']',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });

    it("returns error for multi-line object without dependency field", () => {
      const lines = [
        'my-group = [',
        '  {',
        '    note = "missing dep"',
        '  }',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].message).toBe("Object entry must have a 'dependency' field");
    });

    it("returns error for multi-line object without note field", () => {
      const lines = [
        'my-group = [',
        '  {',
        '    dependency = "org.typelevel::cats-core:^2.10.0"',
        '  }',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].message).toBe("Object entry must have a 'note' field");
    });
  });

  describe("edge cases", () => {
    it("validates dep on same line as closing bracket", () => {
      const lines = [
        'my-group = [',
        '  "bad"]',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].message).toBe('Malformed dependency: expected format "org:artifact" or "org::artifact"');
    });

    it("handles empty dependencies array on single line in advanced block", () => {
      const lines = [
        'sbt-build {',
        '  scala-version = "~2.12.21"',
        '  dependencies = []',
        '}',
        '',
        'sbt-me {',
        '  scala-version = "~2.12.21"',
        '  dependencies = [',
        '    "org.typelevel::cats-core:2.10.0"',
        '  ]',
        '}',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });

    it("handles empty simple group array on single line", () => {
      const lines = [
        'my-group = []',
        'other-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
      ];
      expect(parseDiagnostics(lines)).toEqual([]);
    });

    it("resets state correctly across multiple sequential groups", () => {
      const lines = [
        'group-a = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
        'group-b = [',
        '  "bad"',
        ']',
      ];
      const result = parseDiagnostics(lines);
      expect(result).toHaveLength(1);
      expect(result[0].range.startLine).toBe(4);
    });
  });
});
