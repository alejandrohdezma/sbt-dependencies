import { describe, it, expect } from "vitest";
import { getQuickFixes } from "./quickfix";

describe("getQuickFixes", () => {
  it('returns a delete-line fix for the duplicate diagnostic', () => {
    const result = getQuickFixes("Duplicate dependency in group", 3);
    expect(result).toEqual([{ kind: "delete-line", title: "Remove duplicate dependency", deleteLineIndex: 3 }]);
  });

  it('returns a delete-line fix for the empty dependency diagnostic', () => {
    const result = getQuickFixes("Empty dependency string", 7);
    expect(result).toEqual([{ kind: "delete-line", title: "Remove empty dependency", deleteLineIndex: 7 }]);
  });

  it("returns a replace-range fix for the BOM-pinned hint", () => {
    const result = getQuickFixes("jackson-databind is pinned by com.fasterxml.jackson:jackson-bom at 2.17.0", 5);
    expect(result).toEqual([{ kind: "replace-range", title: 'Replace version with "*"', newText: "*" }]);
  });

  it("returns empty array for unrecognized diagnostic", () => {
    expect(getQuickFixes("Some other message", 0)).toEqual([]);
  });

  it("returns empty array for malformed dependency diagnostic", () => {
    expect(getQuickFixes('Malformed dependency: expected format "org:artifact" or "org::artifact"', 1)).toEqual([]);
  });

  it("uses the provided line index in the delete descriptor", () => {
    const result = getQuickFixes("Duplicate dependency in group", 42);
    expect(result[0]).toMatchObject({ kind: "delete-line", deleteLineIndex: 42 });
  });
});
