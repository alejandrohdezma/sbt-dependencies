import { describe, it, expect } from "vitest";
import { getQuickFixes } from "./quickfix";

describe("getQuickFixes", () => {
  it('returns "Remove duplicate dependency" for duplicate diagnostic', () => {
    const result = getQuickFixes("Duplicate dependency in group", 3);
    expect(result).toEqual([{ title: "Remove duplicate dependency", deleteLineIndex: 3 }]);
  });

  it('returns "Remove empty dependency" for empty dependency diagnostic', () => {
    const result = getQuickFixes("Empty dependency string", 7);
    expect(result).toEqual([{ title: "Remove empty dependency", deleteLineIndex: 7 }]);
  });

  it("returns empty array for unrecognized diagnostic", () => {
    expect(getQuickFixes("Some other message", 0)).toEqual([]);
  });

  it("returns empty array for malformed dependency diagnostic", () => {
    expect(getQuickFixes('Malformed dependency: expected format "org:artifact" or "org::artifact"', 1)).toEqual([]);
  });

  it("uses the provided line index in the descriptor", () => {
    const result = getQuickFixes("Duplicate dependency in group", 42);
    expect(result[0].deleteLineIndex).toBe(42);
  });
});
