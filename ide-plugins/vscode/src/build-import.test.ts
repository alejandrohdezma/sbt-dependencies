import { describe, it, expect } from "vitest";
import { shouldPromptImport } from "./build-import";

describe("shouldPromptImport", () => {
  it("prompts when stale, Metals is installed and the content is new", () => {
    expect(shouldPromptImport(true, true, undefined, "abc")).toBe(true);
  });

  it("prompts again when the content changed since the last prompt", () => {
    expect(shouldPromptImport(true, true, "abc", "def")).toBe(true);
  });

  it("does not prompt twice for the same content", () => {
    expect(shouldPromptImport(true, true, "abc", "abc")).toBe(false);
  });

  it("does not prompt when the buffer matches the last import", () => {
    expect(shouldPromptImport(false, true, undefined, "abc")).toBe(false);
  });

  it("does not prompt when staleness is unknown (no dump)", () => {
    expect(shouldPromptImport(undefined, true, undefined, "abc")).toBe(false);
  });

  it("does not prompt when Metals is not installed", () => {
    expect(shouldPromptImport(true, false, undefined, "abc")).toBe(false);
  });
});
