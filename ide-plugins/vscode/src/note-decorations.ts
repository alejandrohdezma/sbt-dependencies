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

/** Matches a single-line object entry with overrides but no note or scala-filter: `{ dependency = "...", overrides = true }` */
const overridesObjectPattern =
  /(\{\s*dependency\s*=\s*)"([^"]*)"(\s*,\s*overrides\s*=\s*(true)[^}]*\})/;

/** The collapsible annotations, in priority order, each with the text shown after the dependency string. */
const collapsible: { pattern: RegExp; text: (value: string) => string }[] = [
  { pattern: singleLineObjectPattern, text: value => value },
  { pattern: scalaFilterObjectPattern, text: value => `only for Scala ${value}` },
  { pattern: overridesObjectPattern, text: () => "overrides" },
];

/**
 * Scans lines from a `dependencies.conf` file and returns decoration data
 * for single-line object entries whose annotation can be shown as a trailing comment:
 * a `note`, else a `scala-filter`, else `overrides = true`.
 *
 * Only processes entries inside dependency array contexts (simple-group
 * `= [...]` or advanced-group `dependencies = [...]`).
 */
export function parseNoteDecorations(lines: string[]): NoteDecorationData[] {
  const results: NoteDecorationData[] = [];

  for (const event of walkDocument(lines)) {
    if (event.type !== "single-line-object") continue;

    // Use the specialized regexes that capture prefix/suffix ranges for decoration hiding
    let match: RegExpExecArray | null = null;
    let text: (value: string) => string = value => value;
    for (const candidate of collapsible) {
      match = candidate.pattern.exec(event.rawLine);
      if (match) {
        text = candidate.text;
        break;
      }
    }
    if (!match) continue;

    const fullMatchStart = match.index;
    const prefix = match[1]; // `{ dependency = `
    const depString = match[2]; // the dependency string
    const suffix = match[3]; // `, note/scala-filter/overrides = ... }`
    const fieldText = match[4]; // the note content, scala-filter value or `true`

    // Prefix ends before the opening `"`, suffix starts after the closing `"`
    const prefixEnd = fullMatchStart + prefix.length;
    const suffixStart = prefixEnd + 1 + depString.length + 1; // 1 for `"` on each side
    const suffixEnd = suffixStart + suffix.length;

    results.push({
      line: event.lineIndex,
      prefixRange: { startCol: fullMatchStart, endCol: prefixEnd },
      suffixRange: { startCol: suffixStart, endCol: suffixEnd },
      noteText: text(fieldText),
    });
  }

  return results;
}
