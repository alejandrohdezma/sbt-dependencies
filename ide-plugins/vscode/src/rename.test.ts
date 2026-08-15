import { describe, it, expect } from "vitest";
import { prepareVariableRename, computeVariableRenameEdits } from "./rename";

describe("prepareVariableRename", () => {
  it("returns name range when cursor is on variable name", () => {
    const lines = ['  "org::art:{{myVar}}"'];
    //                         ^cursor col 15 is on 'V'
    const result = prepareVariableRename(lines, 0, 15);
    expect(result).toBeDefined();
    expect(result).toEqual({
      startLine: 0,
      startCol: 14,
      endLine: 0,
      endCol: 19,
    });
  });

  it("returns name range when cursor is on {{ braces", () => {
    const lines = ['  "org::art:{{myVar}}"'];
    //                         ^cursor col 12 is on first '{'
    const result = prepareVariableRename(lines, 0, 12);
    expect(result).toBeDefined();
    expect(result).toEqual({
      startLine: 0,
      startCol: 14,
      endLine: 0,
      endCol: 19,
    });
  });

  it("returns name range when cursor is on }} braces", () => {
    const lines = ['  "org::art:{{myVar}}"'];
    //                                  ^cursor col 19 is on first '}'
    const result = prepareVariableRename(lines, 0, 19);
    expect(result).toBeDefined();
    expect(result).toEqual({
      startLine: 0,
      startCol: 14,
      endLine: 0,
      endCol: 19,
    });
  });

  it("returns undefined when cursor is elsewhere", () => {
    const lines = ['  "org::art:{{myVar}}"'];
    const result = prepareVariableRename(lines, 0, 5);
    expect(result).toBeUndefined();
  });

  it("returns undefined for line without variables", () => {
    const lines = ['  "org::art:1.0.0"'];
    const result = prepareVariableRename(lines, 0, 5);
    expect(result).toBeUndefined();
  });
});

describe("computeVariableRenameEdits", () => {
  it("returns edits for all usages of a variable", () => {
    const lines = [
      '  "org::art:{{myVar}}"',
      '  "other::dep:{{myVar}}"',
      '  "third::pkg:{{otherVar}}"',
    ];
    const result = computeVariableRenameEdits(lines, 0, 15, "newVar");
    expect(result).toBeDefined();
    expect(result!.edits).toHaveLength(2);
    expect(result!.edits[0]).toEqual({ line: 0, startCol: 14, endCol: 19, newText: "newVar" });
    expect(result!.edits[1]).toEqual({ line: 1, startCol: 16, endCol: 21, newText: "newVar" });
  });

  it("strips {{ }} from newName if user includes them", () => {
    const lines = ['  "org::art:{{myVar}}"'];
    const result = computeVariableRenameEdits(lines, 0, 15, "{{newVar}}");
    expect(result).toBeDefined();
    expect(result!.edits).toHaveLength(1);
    expect(result!.edits[0].newText).toBe("newVar");
  });

  it("edit ranges cover only the name inside braces", () => {
    const lines = ['  "org::art:{{myVar}}"'];
    const result = computeVariableRenameEdits(lines, 0, 15, "x");
    expect(result).toBeDefined();
    const edit = result!.edits[0];
    const original = lines[0].slice(edit.startCol, edit.endCol);
    expect(original).toBe("myVar");
  });

  it("returns undefined when cursor is not on a variable", () => {
    const lines = ['  "org::art:1.0.0"'];
    const result = computeVariableRenameEdits(lines, 0, 5, "newVar");
    expect(result).toBeUndefined();
  });
});
