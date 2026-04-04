import { walkDocument } from "./parser";

export interface NoteDecorationData {
  line: number;
  /** Range of the `{ dependency = ` prefix to hide (before the opening `"`). */
  prefixRange: { startCol: number; endCol: number };
  /** Range of the `, note = "..." }` suffix to hide (after the closing `"`). */
  suffixRange: { startCol: number; endCol: number };
  /** The note text to display as an after-decoration. */
  noteText: string;
}

/** Matches a single-line object entry: `{ dependency = "...", note = "..." [, ...] }` */
const singleLineObjectPattern =
  /(\{\s*dependency\s*=\s*)"([^"]*)"(\s*,\s*note\s*=\s*"([^"]*)"[^}]*\})/;

/** Matches a single-line object entry with scala-filter but no note: `{ dependency = "...", scala-filter = "..." }` */
const scalaFilterObjectPattern =
  /(\{\s*dependency\s*=\s*)"([^"]*)"(\s*,\s*scala-filter\s*=\s*"([^"]*)"[^}]*\})/;

/**
 * Scans lines from a `dependencies.conf` file and returns decoration data
 * for single-line object entries that have both `dependency` and `note` fields.
 *
 * Only processes entries inside dependency array contexts (simple-group
 * `= [...]` or advanced-group `dependencies = [...]`).
 */
export function parseNoteDecorations(lines: string[]): NoteDecorationData[] {
  const results: NoteDecorationData[] = [];

  for (const event of walkDocument(lines)) {
    if (event.type !== "single-line-object") continue;

    // Use the specialized regex that captures prefix/suffix ranges for decoration hiding
    const match = singleLineObjectPattern.exec(event.rawLine) ?? scalaFilterObjectPattern.exec(event.rawLine);
    if (!match) continue;

    const isScalaFilter = !singleLineObjectPattern.test(event.rawLine);
    const fullMatchStart = match.index;
    const prefix = match[1]; // `{ dependency = `
    const depString = match[2]; // the dependency string
    const suffix = match[3]; // `, note/scala-filter = "..." }`
    const fieldText = match[4]; // the note content or scala-filter value

    // Prefix ends before the opening `"`, suffix starts after the closing `"`
    const prefixEnd = fullMatchStart + prefix.length;
    const suffixStart = prefixEnd + 1 + depString.length + 1; // 1 for `"` on each side
    const suffixEnd = suffixStart + suffix.length;

    results.push({
      line: event.lineIndex,
      prefixRange: { startCol: fullMatchStart, endCol: prefixEnd },
      suffixRange: { startCol: suffixStart, endCol: suffixEnd },
      noteText: isScalaFilter ? `only for Scala ${fieldText}` : fieldText,
    });
  }

  return results;
}
