export interface NoteDecorationData {
  line: number;
  /** Range of the `{ dependency = ` prefix to hide (before the opening `"`). */
  prefixRange: { startCol: number; endCol: number };
  /** Range of the `, note = "..." }` suffix to hide (after the closing `"`). */
  suffixRange: { startCol: number; endCol: number };
  /** The note text to display as an after-decoration. */
  noteText: string;
}

/** Matches a single-line object entry: `{ dependency = "...", note = "..." }` */
const singleLineObjectPattern =
  /(\{\s*dependency\s*=\s*)"([^"]*)"(\s*,\s*note\s*=\s*"([^"]*)"\s*\})/;

/**
 * Scans lines from a `dependencies.conf` file and returns decoration data
 * for single-line object entries that have both `dependency` and `note` fields.
 *
 * Only processes entries inside dependency array contexts (simple-group
 * `= [...]` or advanced-group `dependencies = [...]`).
 */
export function parseNoteDecorations(lines: string[]): NoteDecorationData[] {
  const results: NoteDecorationData[] = [];
  let inArray = false;
  let inAdvancedBlock = false;
  let inBlockComment = false;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // Strip block/line comments to get effective line
    let pos = 0;
    let effectiveLine = "";

    while (pos < line.length) {
      if (inBlockComment) {
        const endIdx = line.indexOf("*/", pos);
        if (endIdx === -1) {
          pos = line.length;
        } else {
          inBlockComment = false;
          pos = endIdx + 2;
        }
      } else {
        const startIdx = line.indexOf("/*", pos);
        if (startIdx === -1) {
          effectiveLine += line.slice(pos);
          pos = line.length;
        } else {
          effectiveLine += line.slice(pos, startIdx);
          inBlockComment = true;
          pos = startIdx + 2;
        }
      }
    }

    const commentIdx = Math.min(
      effectiveLine.indexOf("//") === -1 ? Infinity : effectiveLine.indexOf("//"),
      effectiveLine.indexOf("#") === -1 ? Infinity : effectiveLine.indexOf("#")
    );
    if (commentIdx !== Infinity) {
      effectiveLine = effectiveLine.slice(0, commentIdx);
    }

    // State machine for tracking array context
    if (!inArray && !inAdvancedBlock) {
      if (/^\s*[\w][\w.-]*\s*=\s*\[/.test(effectiveLine)) {
        inArray = !effectiveLine.includes("]");
      } else if (/^\s*[\w][\w.-]*\s*\{/.test(effectiveLine)) {
        inAdvancedBlock = true;
      }
      continue;
    }

    if (inAdvancedBlock && !inArray) {
      if (/^\s*dependencies\s*=\s*\[/.test(effectiveLine)) {
        inArray = !effectiveLine.includes("]");
      } else if (effectiveLine.includes("}")) {
        inAdvancedBlock = false;
      }
      continue;
    }

    if (inArray) {
      if (effectiveLine.includes("]")) {
        inArray = false;
        if (!inAdvancedBlock) continue;
        // Stay in advanced block after dependencies array closes
        continue;
      }

      // Check for single-line object entry with both dependency and note
      const match = singleLineObjectPattern.exec(effectiveLine);
      if (match) {
        const fullMatchStart = match.index;
        const prefix = match[1]; // `{ dependency = `
        const depString = match[2]; // the dependency string
        const suffix = match[3]; // `, note = "..." }`
        const noteText = match[4]; // the note content

        // Prefix ends before the opening `"`, suffix starts after the closing `"`
        const prefixEnd = fullMatchStart + prefix.length;
        const suffixStart = prefixEnd + 1 + depString.length + 1; // 1 for `"` on each side
        const suffixEnd = suffixStart + suffix.length;

        results.push({
          line: i,
          prefixRange: { startCol: fullMatchStart, endCol: prefixEnd },
          suffixRange: { startCol: suffixStart, endCol: suffixEnd },
          noteText,
        });
      }
    }
  }

  return results;
}
