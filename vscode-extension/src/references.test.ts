import { describe, it, expect } from "vitest";
import { findReferences } from "./references";

describe("findReferences", () => {
  describe("variable references", () => {
    it("finds all occurrences of {{catsVersion}} across lines/groups", () => {
      const lines = [
        'core = [',
        '  "org.typelevel::cats-core:{{catsVersion}}"',
        '  "org.typelevel::cats-effect:3.5.3"',
        ']',
        'extras = [',
        '  "org.typelevel::cats-free:{{catsVersion}}"',
        ']',
      ];
      const result = findReferences(lines, 1, 35);
      expect(result).toHaveLength(2);
      expect(result![0]).toEqual({ line: 1, startCol: 28, endCol: 43 });
      expect(result![1]).toEqual({ line: 5, startCol: 28, endCol: 43 });
    });

    it("returns single-element array for variable appearing once", () => {
      const lines = [
        'core = [',
        '  "org.typelevel::cats-core:{{catsVersion}}"',
        ']',
      ];
      const result = findReferences(lines, 1, 35);
      expect(result).toHaveLength(1);
      expect(result![0]).toEqual({ line: 1, startCol: 28, endCol: 43 });
    });

    it("identifies variable when cursor is on {{ braces", () => {
      const lines = [
        '  "org.typelevel::cats-core:{{ver}}"',
      ];
      // cursor on the first `{`
      const result = findReferences(lines, 0, 28);
      expect(result).toBeDefined();
      expect(result).toHaveLength(1);
      expect(result![0]).toEqual({ line: 0, startCol: 28, endCol: 35 });
    });

    it("finds only the targeted variable when multiple variables exist on same line", () => {
      const lines = [
        '  "{{alpha}}:{{beta}}"',
      ];
      // cursor on {{alpha}} (col 3)
      const resultAlpha = findReferences(lines, 0, 3);
      expect(resultAlpha).toHaveLength(1);
      expect(resultAlpha![0]).toEqual({ line: 0, startCol: 3, endCol: 12 });

      // cursor on {{beta}} (col 14)
      const resultBeta = findReferences(lines, 0, 14);
      expect(resultBeta).toHaveLength(1);
      expect(resultBeta![0]).toEqual({ line: 0, startCol: 13, endCol: 21 });
    });

    it("variable ranges span full {{varName}} including braces", () => {
      const lines = [
        '  "org::art:{{myVar}}"',
        '  "org::art:{{myVar}}"',
      ];
      const result = findReferences(lines, 0, 15);
      expect(result).toHaveLength(2);
      for (const ref of result!) {
        const text = lines[ref.line].slice(ref.startCol, ref.endCol);
        expect(text).toBe("{{myVar}}");
      }
    });
  });

  describe("dependency references", () => {
    it("finds all same org::artifact references regardless of version", () => {
      const lines = [
        'core = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
        'extras = [',
        '  "org.typelevel::cats-core:^2.11.0"',
        ']',
      ];
      const result = findReferences(lines, 1, 5);
      expect(result).toHaveLength(2);
      expect(result![0]).toEqual({ line: 1, startCol: 3, endCol: 27 });
      expect(result![1]).toEqual({ line: 4, startCol: 3, endCol: 27 });
    });

    it("finds same dependency in simple group and advanced group", () => {
      const lines = [
        'simple = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
        'advanced {',
        '  dependencies = [',
        '    "org.typelevel::cats-core:=2.10.0"',
        '  ]',
        '}',
      ];
      const result = findReferences(lines, 1, 5);
      expect(result).toHaveLength(2);
      expect(result![0].line).toBe(1);
      expect(result![1].line).toBe(5);
    });

    it("matches dependencies with different version markers", () => {
      const lines = [
        '  "org.typelevel::cats-core:^2.10.0"',
        '  "org.typelevel::cats-core:=2.10.0"',
        '  "org.typelevel::cats-core:{{ver}}"',
      ];
      // cursor on the org part of the dependency (not on the {{ver}})
      const result = findReferences(lines, 0, 5);
      expect(result).toHaveLength(3);
    });

    it("treats Java dep (:) as distinct from Scala dep (::)", () => {
      const lines = [
        '  "com.typesafe:config:1.4.3"',
        '  "com.typesafe::config:1.0.0"',
      ];
      const result = findReferences(lines, 0, 5);
      expect(result).toHaveLength(1);
      expect(result![0].line).toBe(0);
    });

    it("dependency ranges span only org::artifact, not version", () => {
      const lines = [
        '  "org.typelevel::cats-core:2.10.0"',
      ];
      const result = findReferences(lines, 0, 5);
      expect(result).toHaveLength(1);
      const ref = result![0];
      const text = lines[ref.line].slice(ref.startCol, ref.endCol);
      expect(text).toBe("org.typelevel::cats-core");
    });

    it("works for dependency with no version", () => {
      const lines = [
        '  "org.typelevel::cats-core"',
        '  "org.typelevel::cats-core:2.10.0"',
      ];
      const result = findReferences(lines, 0, 5);
      expect(result).toHaveLength(2);
      for (const ref of result!) {
        const text = lines[ref.line].slice(ref.startCol, ref.endCol);
        expect(text).toBe("org.typelevel::cats-core");
      }
    });
  });

  describe("variable priority over dependency", () => {
    it("matches variable when cursor is on {{ver}} inside a dependency", () => {
      const lines = [
        '  "org.typelevel::cats-core:{{ver}}"',
        '  "io.circe::circe-core:{{ver}}"',
      ];
      // cursor on {{ver}} in line 0
      const result = findReferences(lines, 0, 31);
      expect(result).toHaveLength(2);
      // Should return variable references, not dependency references
      const text0 = lines[result![0].line].slice(result![0].startCol, result![0].endCol);
      const text1 = lines[result![1].line].slice(result![1].startCol, result![1].endCol);
      expect(text0).toBe("{{ver}}");
      expect(text1).toBe("{{ver}}");
    });

    it("matches dependency when cursor is on org::artifact portion", () => {
      const lines = [
        '  "org.typelevel::cats-core:{{ver}}"',
      ];
      // cursor on org portion
      const result = findReferences(lines, 0, 5);
      expect(result).toBeDefined();
      const text = lines[result![0].line].slice(result![0].startCol, result![0].endCol);
      expect(text).toBe("org.typelevel::cats-core");
    });
  });

  describe("object format entries", () => {
    it("finds references across string and object entries", () => {
      const lines = [
        'core = [',
        '  "org.typelevel::cats-core:2.10.0"',
        ']',
        'extras = [',
        '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "pinned" }',
        ']',
      ];
      // cursor on org.typelevel in the string entry
      const result = findReferences(lines, 1, 5);
      expect(result).toHaveLength(2);
      expect(result![0].line).toBe(1);
      expect(result![1].line).toBe(4);
    });
  });

  describe("no match", () => {
    it("returns undefined when cursor is not on any entity", () => {
      const lines = [
        '# just a comment',
        '',
        'core = [',
      ];
      expect(findReferences(lines, 0, 5)).toBeUndefined();
      expect(findReferences(lines, 1, 0)).toBeUndefined();
      expect(findReferences(lines, 2, 0)).toBeUndefined();
    });
  });

  describe("real-world multi-group file", () => {
    it("handles shared variables and dependencies across groups", () => {
      const lines = [
        '# Configuration file',
        '',
        'core = [',
        '  "org.typelevel::cats-core:{{catsVersion}}"',
        '  "org.typelevel::cats-effect:3.5.3"',
        ']',
        '',
        'http {',
        '  dependencies = [',
        '    "org.http4s::http4s-core:0.23.25"',
        '    "org.typelevel::cats-core:{{catsVersion}}"',
        '  ]',
        '}',
        '',
        'testing = [',
        '  "org.typelevel::cats-core:{{catsVersion}}"',
        ']',
      ];

      // Find all {{catsVersion}} references
      const varRefs = findReferences(lines, 3, 35);
      expect(varRefs).toHaveLength(3);
      expect(varRefs![0].line).toBe(3);
      expect(varRefs![1].line).toBe(10);
      expect(varRefs![2].line).toBe(15);

      // Find all org.typelevel::cats-core references
      const depRefs = findReferences(lines, 3, 5);
      expect(depRefs).toHaveLength(3);
      expect(depRefs![0].line).toBe(3);
      expect(depRefs![1].line).toBe(10);
      expect(depRefs![2].line).toBe(15);

      // cats-effect only appears once
      const effectRefs = findReferences(lines, 4, 5);
      expect(effectRefs).toHaveLength(1);
      expect(effectRefs![0].line).toBe(4);

      // http4s only appears once
      const http4sRefs = findReferences(lines, 9, 8);
      expect(http4sRefs).toHaveLength(1);
      expect(http4sRefs![0].line).toBe(9);
    });
  });
});
