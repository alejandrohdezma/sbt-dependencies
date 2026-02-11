import { describe, it, expect } from "vitest";
import { parseDocumentSymbols } from "./symbols";

describe("parseDocumentSymbols", () => {
  describe("simple groups", () => {
    it("returns 1 group with N children", () => {
      const lines = [
        'my-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        '  "com.typesafe:config:1.4.3"',
        ']',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result).toHaveLength(1);
      expect(result[0].name).toBe("my-group");
      expect(result[0].kind).toBe("group");
      expect(result[0].children).toHaveLength(2);
      expect(result[0].children![0].kind).toBe("dependency");
      expect(result[0].children![1].kind).toBe("dependency");
    });

    it("extracts group name correctly", () => {
      const lines = [
        'my-awesome.group_1 = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result[0].name).toBe("my-awesome.group_1");
    });

    it("extracts dependency names as raw quoted string content", () => {
      const lines = [
        'my-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        '  "com.typesafe:config:1.4.3"',
        ']',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result[0].children![0].name).toBe("org.typelevel::cats-core:2.10.0");
      expect(result[0].children![1].name).toBe("com.typesafe:config:1.4.3");
    });
  });

  describe("advanced groups", () => {
    it("returns 1 group with N children", () => {
      const lines = [
        'my-group {',
        '  dependencies = [',
        '    "org.typelevel::cats-core:2.10.0"',
        '    "io.circe::circe-core:=0.14.6"',
        '  ]',
        '}',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result).toHaveLength(1);
      expect(result[0].name).toBe("my-group");
      expect(result[0].kind).toBe("group");
      expect(result[0].children).toHaveLength(2);
      expect(result[0].children![0].name).toBe("org.typelevel::cats-core:2.10.0");
      expect(result[0].children![1].name).toBe("io.circe::circe-core:=0.14.6");
    });

    it("ignores non-dependency arrays", () => {
      const lines = [
        'my-group {',
        '  scala-versions = ["~2.13.12"]',
        '  dependencies = [',
        '    "org.typelevel::cats-core:2.10.0"',
        '  ]',
        '}',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result[0].children).toHaveLength(1);
      expect(result[0].children![0].name).toBe("org.typelevel::cats-core:2.10.0");
    });
  });

  describe("multiple groups", () => {
    it("returns all groups in order (simple + advanced)", () => {
      const lines = [
        'simple-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
        'advanced-group {',
        '  dependencies = [',
        '    "io.circe::circe-core:=0.14.6"',
        '  ]',
        '}',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result).toHaveLength(2);
      expect(result[0].name).toBe("simple-group");
      expect(result[1].name).toBe("advanced-group");
    });
  });

  describe("empty arrays", () => {
    it("returns group with 0 children for empty simple array", () => {
      const lines = ['my-group = []'];
      const result = parseDocumentSymbols(lines);
      expect(result).toHaveLength(1);
      expect(result[0].name).toBe("my-group");
      expect(result[0].children).toHaveLength(0);
    });

    it("returns group with 0 children for empty advanced dependencies", () => {
      const lines = [
        'my-group {',
        '  dependencies = []',
        '}',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result).toHaveLength(1);
      expect(result[0].children).toHaveLength(0);
    });
  });

  describe("comment handling", () => {
    it("returns no symbols when group is inside a block comment", () => {
      const lines = [
        '/* my-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        '] */',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result).toHaveLength(0);
    });

    it("returns symbols for groups after a block comment ends", () => {
      const lines = [
        '/* commented-group = [',
        '  "bad"',
        '] */',
        'real-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result).toHaveLength(1);
      expect(result[0].name).toBe("real-group");
    });
  });

  describe("range values", () => {
    it("sets correct range for simple group", () => {
      const lines = [
        'my-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result[0].range.startLine).toBe(0);
      expect(result[0].range.startCol).toBe(0);
      expect(result[0].range.endLine).toBe(2);
    });

    it("sets correct range for advanced group", () => {
      const lines = [
        'advanced-group {',
        '  dependencies = [',
        '    "org.typelevel::cats-core:2.10.0"',
        '  ]',
        '}',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result[0].range.startLine).toBe(0);
      expect(result[0].range.startCol).toBe(0);
      expect(result[0].range.endLine).toBe(4);
    });

    it("sets correct range for dependency children", () => {
      const lines = [
        'my-group = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
      ];
      const result = parseDocumentSymbols(lines);
      const dep = result[0].children![0];
      expect(dep.range.startLine).toBe(1);
      expect(dep.range.startCol).toBe(3); // after `  "`
      expect(dep.range.endLine).toBe(1);
      expect(dep.range.endCol).toBe(3 + "org.typelevel::cats-core:2.10.0".length);
    });

    it("handles indented group names", () => {
      const lines = [
        '  indented = [',
        '    "org.typelevel::cats-core:2.10.0"',
        '  ]',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result[0].range.startCol).toBe(2);
    });
  });

  describe("real-world example", () => {
    it("handles mixed groups, comments, and empty arrays", () => {
      const lines = [
        '# Configuration file for dependencies',
        '',
        'core = [',
        '  "org.typelevel::cats-core:2.10.0"',
        '  "org.typelevel::cats-effect:3.5.3"',
        ']',
        '',
        '/* temporarily disabled',
        'disabled-group = [',
        '  "bad::dep"',
        '] */',
        '',
        'empty-group = []',
        '',
        'sbt-build {',
        '  scala-version = "~2.12.21"',
        '  dependencies = [',
        '    "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"',
        '  ]',
        '}',
      ];
      const result = parseDocumentSymbols(lines);
      expect(result).toHaveLength(3);

      expect(result[0].name).toBe("core");
      expect(result[0].children).toHaveLength(2);

      expect(result[1].name).toBe("empty-group");
      expect(result[1].children).toHaveLength(0);

      expect(result[2].name).toBe("sbt-build");
      expect(result[2].children).toHaveLength(1);
      expect(result[2].children![0].name).toBe("ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin");
    });
  });
});
