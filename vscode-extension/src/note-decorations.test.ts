import { describe, it, expect } from "vitest";
import { parseNoteDecorations } from "./note-decorations";

describe("parseNoteDecorations", () => {
  it("returns decoration data for single-line object entry in simple group", () => {
    const lines = [
      'my-group = [',
      '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "v3 drops Scala 2.12" }',
      ']',
    ];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(1);
    expect(results[0].line).toBe(1);
    expect(results[0].noteText).toBe("v3 drops Scala 2.12");

    // Verify prefix range covers `{ dependency = ` (before the opening quote)
    const prefixText = lines[1].slice(results[0].prefixRange.startCol, results[0].prefixRange.endCol);
    expect(prefixText).toBe('{ dependency = ');

    // Verify the visible part (between prefix end and suffix start) includes the quoted dep string
    const visiblePart = lines[1].slice(results[0].prefixRange.endCol, results[0].suffixRange.startCol);
    expect(visiblePart).toBe('"org.typelevel::cats-core:^2.10.0"');

    // Verify suffix range covers `, note = "v3 drops Scala 2.12" }`
    const suffixText = lines[1].slice(results[0].suffixRange.startCol, results[0].suffixRange.endCol);
    expect(suffixText).toBe(', note = "v3 drops Scala 2.12" }');
  });

  it("returns decoration data for entry in advanced group", () => {
    const lines = [
      'my-project {',
      '  scala-versions = ["2.13.12"]',
      '  dependencies = [',
      '    { dependency = "org.typelevel::cats-effect:^3.5.4", note = "v4 drops CE2 compat" }',
      '  ]',
      '}',
    ];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(1);
    expect(results[0].line).toBe(3);
    expect(results[0].noteText).toBe("v4 drops CE2 compat");
  });

  it("skips plain string entries", () => {
    const lines = [
      'my-group = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(0);
  });

  it("skips object entries without note field", () => {
    const lines = [
      'my-group = [',
      '  { dependency = "org.typelevel::cats-core:^2.10.0" }',
      ']',
    ];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(0);
  });

  it("skips entries outside dependency arrays", () => {
    const lines = [
      '// { dependency = "org::art:1.0.0", note = "outside" }',
      'my-group = [',
      '  "org.typelevel::cats-core:2.10.0"',
      ']',
    ];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(0);
  });

  it("handles multiple entries across groups", () => {
    const lines = [
      'group-a = [',
      '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "note A" }',
      ']',
      '',
      'group-b = [',
      '  { dependency = "co.fs2::fs2-core:=3.9.4", note = "note B" }',
      ']',
    ];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(2);
    expect(results[0].noteText).toBe("note A");
    expect(results[1].noteText).toBe("note B");
  });

  it("handles mixed entries (plain strings and objects)", () => {
    const lines = [
      'my-group = [',
      '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "pinned" }',
      '  "co.fs2::fs2-core:3.9.4"',
      '  { dependency = "io.circe::circe-core:=0.14.6", note = "waiting for 1.0" }',
      ']',
    ];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(2);
    expect(results[0].line).toBe(1);
    expect(results[1].line).toBe(3);
  });

  it("handles block comments correctly", () => {
    const lines = [
      'my-group = [',
      '  /* { dependency = "org::art:1.0.0", note = "inside comment" } */',
      '  { dependency = "org.typelevel::cats-core:^2.10.0", note = "real" }',
      ']',
    ];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(1);
    expect(results[0].noteText).toBe("real");
  });

  it("handles empty arrays", () => {
    const lines = ['my-group = []'];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(0);
  });

  it("preserves exact column positions", () => {
    const line = '    { dependency = "io.circe::circe-core:=0.14.6", note = "waiting" }';
    const lines = ['my-group = [', line, ']'];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(1);

    const r = results[0];
    // The visible part (between hidden prefix and hidden suffix) includes quotes
    const visiblePart = line.slice(r.prefixRange.endCol, r.suffixRange.startCol);
    expect(visiblePart).toBe('"io.circe::circe-core:=0.14.6"');

    // The hidden prefix covers everything before the opening quote
    const prefix = line.slice(r.prefixRange.startCol, r.prefixRange.endCol);
    expect(prefix).toBe('{ dependency = ');

    // The hidden suffix covers everything after the closing quote
    const suffix = line.slice(r.suffixRange.startCol, r.suffixRange.endCol);
    expect(suffix).toBe(', note = "waiting" }');
  });

  it("returns decoration data for object with both note and intransitive fields", () => {
    const line = '  { dependency = "org.http4s::http4s-core:=0.23.3", note = "reason", intransitive = true }';
    const lines = ['my-group = [', line, ']'];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(1);
    expect(results[0].noteText).toBe("reason");

    const r = results[0];
    const visiblePart = line.slice(r.prefixRange.endCol, r.suffixRange.startCol);
    expect(visiblePart).toBe('"org.http4s::http4s-core:=0.23.3"');
  });

  it("does not return decoration for intransitive-only object (no note)", () => {
    const lines = [
      'my-group = [',
      '  { dependency = "org.http4s::http4s-core:=0.23.3", intransitive = true }',
      ']',
    ];
    const results = parseNoteDecorations(lines);
    expect(results).toHaveLength(0);
  });
});
