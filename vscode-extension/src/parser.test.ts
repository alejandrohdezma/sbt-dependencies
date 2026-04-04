import { describe, it, expect } from "vitest";
import { walkDocument, stripComments, DocumentEvent } from "./parser";

function collectEvents(text: string): DocumentEvent[] {
  return [...walkDocument(text.split("\n"))];
}

function eventsOfType<T extends DocumentEvent["type"]>(
  text: string,
  type: T
): Extract<DocumentEvent, { type: T }>[] {
  return collectEvents(text).filter((e): e is Extract<DocumentEvent, { type: T }> => e.type === type);
}

describe("stripComments", () => {
  it("strips line comments with //", () => {
    const { effectiveLine } = stripComments('  "org:art:1.0" // a comment', false);
    expect(effectiveLine).toBe('  "org:art:1.0" ');
  });

  it("strips line comments with #", () => {
    const { effectiveLine } = stripComments('  "org:art:1.0" # a comment', false);
    expect(effectiveLine).toBe('  "org:art:1.0" ');
  });

  it("strips block comments on a single line", () => {
    const { effectiveLine, inBlockComment } = stripComments("before /* comment */ after", false);
    expect(effectiveLine).toBe("before  after");
    expect(inBlockComment).toBe(false);
  });

  it("handles block comment start without end", () => {
    const { effectiveLine, inBlockComment } = stripComments("before /* start", false);
    expect(effectiveLine).toBe("before ");
    expect(inBlockComment).toBe(true);
  });

  it("handles block comment continuation", () => {
    const { effectiveLine, inBlockComment } = stripComments("still in comment */ after", true);
    expect(effectiveLine).toBe(" after");
    expect(inBlockComment).toBe(false);
  });

  it("handles block comment spanning full line", () => {
    const { effectiveLine, inBlockComment } = stripComments("middle of block comment", true);
    expect(effectiveLine).toBe("");
    expect(inBlockComment).toBe(true);
  });
});

describe("walkDocument", () => {
  describe("simple groups", () => {
    it("emits group-start and group-end for simple groups", () => {
      const events = collectEvents(
        `my-group = [\n  "org:art:1.0"\n]`
      );
      const starts = events.filter(e => e.type === "group-start");
      const ends = events.filter(e => e.type === "group-end");
      expect(starts).toHaveLength(1);
      expect(ends).toHaveLength(1);
      expect(starts[0]).toMatchObject({
        type: "group-start",
        name: "my-group",
        lineIndex: 0,
        groupKind: "simple",
        singleLine: false,
      });
      expect(ends[0]).toMatchObject({
        type: "group-end",
        lineIndex: 2,
        groupKind: "simple",
      });
    });

    it("emits dependency-string events for plain strings", () => {
      const deps = eventsOfType(
        `group = [\n  "org:art:1.0"\n  "org2::art2:2.0"\n]`,
        "dependency-string"
      );
      expect(deps).toHaveLength(2);
      expect(deps[0]).toMatchObject({ content: "org:art:1.0", lineIndex: 1, arrayKind: "simple" });
      expect(deps[1]).toMatchObject({ content: "org2::art2:2.0", lineIndex: 2, arrayKind: "simple" });
    });

    it("handles single-line groups", () => {
      const events = collectEvents(`group = ["org:art:1.0" "org2:art2:2.0"]`);
      const starts = events.filter(e => e.type === "group-start");
      const deps = events.filter(e => e.type === "dependency-string");
      const ends = events.filter(e => e.type === "group-end");
      expect(starts).toHaveLength(1);
      expect(starts[0]).toMatchObject({ singleLine: true });
      expect(deps).toHaveLength(2);
      expect(ends).toHaveLength(1);
    });

    it("handles empty groups", () => {
      const events = collectEvents(`group = [\n]`);
      const deps = events.filter(e => e.type === "dependency-string");
      expect(deps).toHaveLength(0);
    });
  });

  describe("advanced groups", () => {
    it("emits group-start/end for advanced groups", () => {
      const events = collectEvents(
        `api {\n  scala-version = "3.3.6"\n  dependencies = [\n    "org:art:1.0"\n  ]\n}`
      );
      const starts = events.filter(e => e.type === "group-start");
      const ends = events.filter(e => e.type === "group-end");
      expect(starts).toHaveLength(1);
      expect(starts[0]).toMatchObject({ name: "api", groupKind: "advanced" });
      expect(ends).toHaveLength(1);
      expect(ends[0]).toMatchObject({ groupKind: "advanced" });
    });

    it("emits setting-line for non-dependency fields", () => {
      const settings = eventsOfType(
        `api {\n  scala-version = "3.3.6"\n  dependencies = [\n    "org:art:1.0"\n  ]\n}`,
        "setting-line"
      );
      expect(settings).toHaveLength(1);
      expect(settings[0].effectiveLine).toContain("scala-version");
    });

    it("emits dependency-string with arrayKind 'dependencies'", () => {
      const deps = eventsOfType(
        `api {\n  dependencies = [\n    "org:art:1.0"\n  ]\n}`,
        "dependency-string"
      );
      expect(deps).toHaveLength(1);
      expect(deps[0]).toMatchObject({ content: "org:art:1.0", arrayKind: "dependencies" });
    });

    it("handles single-line dependencies array", () => {
      const deps = eventsOfType(
        `api {\n  dependencies = ["org:art:1.0" "org2:art2:2.0"]\n}`,
        "dependency-string"
      );
      expect(deps).toHaveLength(2);
    });
  });

  describe("single-line objects", () => {
    it("emits single-line-object events", () => {
      const objs = eventsOfType(
        `group = [\n  { dependency = "org:art:1.0", note = "reason" }\n]`,
        "single-line-object"
      );
      expect(objs).toHaveLength(1);
      expect(objs[0]).toMatchObject({
        dependency: "org:art:1.0",
        note: "reason",
        intransitive: false,
      });
    });

    it("detects intransitive field", () => {
      const objs = eventsOfType(
        `group = [\n  { dependency = "org:art:1.0", intransitive = true }\n]`,
        "single-line-object"
      );
      expect(objs).toHaveLength(1);
      expect(objs[0].intransitive).toBe(true);
    });

    it("detects scala-filter field", () => {
      const objs = eventsOfType(
        `group = [\n  { dependency = "org:art:1.0", scala-filter = "2.13" }\n]`,
        "single-line-object"
      );
      expect(objs).toHaveLength(1);
      expect(objs[0].scalaFilter).toBe("2.13");
    });

    it("calculates dependencyStartCol correctly", () => {
      const objs = eventsOfType(
        `group = [\n  { dependency = "org:art:1.0", note = "reason" }\n]`,
        "single-line-object"
      );
      const obj = objs[0];
      expect(obj.dependencyStartCol).toBeDefined();
      const line = `  { dependency = "org:art:1.0", note = "reason" }`;
      expect(line.substring(obj.dependencyStartCol!, obj.dependencyStartCol! + "org:art:1.0".length)).toBe("org:art:1.0");
    });

    it("does not treat {{var}} inside strings as objects", () => {
      const events = collectEvents(
        `group = [\n  "org::art:{{myVar}}"\n]`
      );
      const objs = events.filter(e => e.type === "single-line-object");
      const deps = events.filter(e => e.type === "dependency-string");
      expect(objs).toHaveLength(0);
      expect(deps).toHaveLength(1);
      expect(deps[0]).toMatchObject({ content: "org::art:{{myVar}}" });
    });
  });

  describe("multi-line objects", () => {
    it("emits start, field, and end events", () => {
      const events = collectEvents(
        `group = [\n  {\n    dependency = "org:art:1.0"\n    note = "reason"\n  }\n]`
      );
      const starts = events.filter(e => e.type === "multi-line-object-start");
      const fields = events.filter(e => e.type === "multi-line-object-field");
      const ends = events.filter(e => e.type === "multi-line-object-end");
      expect(starts).toHaveLength(1);
      expect(fields).toHaveLength(4); // opening {, dependency line, note line, closing }
      expect(ends).toHaveLength(1);
    });

    it("aggregates fields in end event", () => {
      const ends = eventsOfType(
        `group = [\n  {\n    dependency = "org:art:1.0"\n    note = "reason"\n    intransitive = true\n  }\n]`,
        "multi-line-object-end"
      );
      expect(ends).toHaveLength(1);
      expect(ends[0]).toMatchObject({
        hasDependency: true,
        dependencyValue: "org:art:1.0",
        hasNote: true,
        noteValue: "reason",
        hasIntransitive: true,
        objectStartLine: 1,
      });
      expect(ends[0].objectLines).toHaveLength(5);
    });

    it("tracks dependency start column", () => {
      const ends = eventsOfType(
        `group = [\n  {\n    dependency = "org:art:1.0"\n  }\n]`,
        "multi-line-object-end"
      );
      expect(ends[0].dependencyStartCol).toBeDefined();
      expect(ends[0].dependencyLineIndex).toBe(2);
    });

    it("emits fields on opening line of object", () => {
      const fields = eventsOfType(
        `group = [\n  { dependency = "org:art:1.0"\n    note = "reason"\n  }\n]`,
        "multi-line-object-field"
      );
      expect(fields[0]).toMatchObject({ field: "dependency", lineIndex: 1 });
    });
  });

  describe("comments", () => {
    it("ignores dependencies inside line comments", () => {
      const deps = eventsOfType(
        `group = [\n  // "org:art:1.0"\n  "org2:art2:2.0"\n]`,
        "dependency-string"
      );
      expect(deps).toHaveLength(1);
      expect(deps[0].content).toBe("org2:art2:2.0");
    });

    it("ignores dependencies inside block comments", () => {
      const deps = eventsOfType(
        `group = [\n  /* "org:art:1.0" */\n  "org2:art2:2.0"\n]`,
        "dependency-string"
      );
      expect(deps).toHaveLength(1);
      expect(deps[0].content).toBe("org2:art2:2.0");
    });

    it("handles multi-line block comments", () => {
      const deps = eventsOfType(
        `group = [\n  /*\n  "org:art:1.0"\n  */\n  "org2:art2:2.0"\n]`,
        "dependency-string"
      );
      expect(deps).toHaveLength(1);
      expect(deps[0].content).toBe("org2:art2:2.0");
    });

    it("ignores groups inside block comments", () => {
      const events = collectEvents(
        `/* hidden = [\n  "org:art:1.0"\n] */\nreal = [\n  "org2:art2:2.0"\n]`
      );
      const starts = events.filter(e => e.type === "group-start");
      expect(starts).toHaveLength(1);
      expect(starts[0]).toMatchObject({ name: "real" });
    });
  });

  describe("multiple groups", () => {
    it("handles consecutive simple and advanced groups", () => {
      const events = collectEvents(
        `simple = [\n  "a:b:1.0"\n]\n\nadvanced {\n  dependencies = [\n    "c:d:2.0"\n  ]\n}`
      );
      const starts = events.filter(e => e.type === "group-start");
      expect(starts).toHaveLength(2);
      expect(starts[0]).toMatchObject({ name: "simple", groupKind: "simple" });
      expect(starts[1]).toMatchObject({ name: "advanced", groupKind: "advanced" });
    });
  });

  describe("edge cases", () => {
    it("handles dependency on closing bracket line", () => {
      const deps = eventsOfType(
        `group = [\n  "a:b:1.0"\n  "c:d:2.0" ]`,
        "dependency-string"
      );
      expect(deps).toHaveLength(2);
    });

    it("handles group names with dots and hyphens", () => {
      const starts = eventsOfType(
        `my.group-name = [\n]`,
        "group-start"
      );
      expect(starts).toHaveLength(1);
      expect(starts[0].name).toBe("my.group-name");
    });

    it("handles advanced group closing } on same line as ]", () => {
      const events = collectEvents(
        `api {\n  scala-version = "3.3.6"\n  dependencies = [\n    "org:art:1.0"\n  ]\n}`
      );
      const groupEnds = events.filter(e => e.type === "group-end");
      expect(groupEnds).toHaveLength(1);
      expect(groupEnds[0]).toMatchObject({ groupKind: "advanced" });
    });
  });
});
