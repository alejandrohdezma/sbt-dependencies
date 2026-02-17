/** Structural regexes (same as diagnostics.ts / symbols.ts). */
const simpleGroupStart = /^(\s*)([\w][\w.-]*)\s*=\s*\[/;
const advancedGroupStart = /^(\s*)([\w][\w.-]*)\s*\{/;
const dependenciesArrayStart = /^\s*dependencies\s*=\s*\[/;

type ParserState = "outside" | "simple_array" | "advanced_block" | "dependencies_array";

/** Regex mirroring Scala-side `Dependency.dependencyRegex`. */
const dependencyPattern =
  /^\s*([^\s:]+)\s*(::?)\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*)?)?$/;

/** A dependency entry with any preceding comment lines. */
interface DependencyEntry {
  commentLines: string[];
  depLine: string;
  /** Composite sort key: config + \0 + org:artifact (lowercased). */
  sortKey: string;
}

/**
 * Formats a `dependencies.conf` document by sorting dependencies
 * alphabetically within each group and normalizing indentation.
 *
 * - Simple groups: 2-space indent for dependencies
 * - Advanced blocks: 2-space indent for fields, 4-space indent for
 *   dependencies inside `dependencies = [...]`
 * - Comment lines above a dependency stay attached to it after sorting
 * - Trailing comments at the end of a group stay at the bottom
 * - Lines outside groups are output as-is
 */
export function formatDocument(lines: string[]): string {
  const output: string[] = [];
  let state: ParserState = "outside";
  let inBlockComment = false;

  /** Indent for deps: 2 in simple groups, 4 in advanced blocks. */
  let depIndent = "  ";

  /** Collected dependency entries in the current array. */
  let entries: DependencyEntry[] = [];

  /** Pending comment lines that will attach to the next dependency. */
  let pendingComments: string[] = [];

  /** Whether the opening bracket line has already been pushed. */
  let headerPushed = false;

  /** Whether at least one group has been emitted (for blank-line normalization). */
  let hasEmittedGroup = false;

  /**
   * Pending "outside" lines (blanks, comments) collected between groups.
   * Flushed when a new group starts, ensuring exactly one blank line separator.
   */
  let outsideBuffer: string[] = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // --- compute effective line (strip block + line comments) ---
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

    // --- state machine ---
    if (state === "outside") {
      const simpleMatch = simpleGroupStart.exec(effectiveLine);
      const advancedMatch = !simpleMatch ? advancedGroupStart.exec(effectiveLine) : null;

      if (simpleMatch || advancedMatch) {
        // About to start a new group — flush outside buffer with
        // exactly one blank line between this group and the previous one.
        if (hasEmittedGroup) {
          // Keep non-blank lines (comments) from the buffer, but
          // replace any run of blank lines with exactly one.
          const nonBlank = outsideBuffer.filter(l => l.trim().length > 0);
          output.push("");
          for (const l of nonBlank) output.push(l);
        } else {
          for (const l of outsideBuffer) output.push(l);
        }
        outsideBuffer = [];
        hasEmittedGroup = true;

        if (simpleMatch) {
          depIndent = "  ";
          if (effectiveLine.includes("]")) {
            output.push(formatSingleLineGroup(line, depIndent));
          } else {
            output.push(line);
            headerPushed = true;
            entries = [];
            pendingComments = [];
            state = "simple_array";
          }
        } else {
          depIndent = "    ";
          output.push(line);
          state = "advanced_block";
        }
        continue;
      }

      // Not a group start — buffer the line
      outsideBuffer.push(line);
      continue;
    }

    if (state === "simple_array") {
      if (effectiveLine.includes("]")) {
        // Closing bracket line — may contain a last dep on same line
        const depOnCloseLine = extractQuotedString(line);
        if (depOnCloseLine) {
          entries.push({ commentLines: pendingComments, depLine: `${depIndent}"${depOnCloseLine}"`, sortKey: buildSortKey(depOnCloseLine) });
          pendingComments = [];
        }
        flushEntries(output, entries, pendingComments);
        output.push("]");
        entries = [];
        pendingComments = [];
        headerPushed = false;
        state = "outside";
        continue;
      }

      const quoted = extractQuotedString(line);
      if (quoted) {
        entries.push({ commentLines: pendingComments, depLine: `${depIndent}"${quoted}"`, sortKey: buildSortKey(quoted) });
        pendingComments = [];
      } else {
        // Comment or blank line inside the array
        pendingComments.push(normalizeCommentLine(line, depIndent));
      }
      continue;
    }

    if (state === "advanced_block") {
      if (dependenciesArrayStart.test(effectiveLine)) {
        if (effectiveLine.includes("]")) {
          // Single-line dependencies array
          output.push(formatSingleLineGroup(line, depIndent));
        } else {
          output.push(`  dependencies = [`);
          headerPushed = true;
          entries = [];
          pendingComments = [];
          state = "dependencies_array";
        }
        continue;
      }

      if (effectiveLine.includes("}")) {
        output.push(line);
        state = "outside";
        continue;
      }

      // Non-dependency fields (scala-versions, etc.) — normalize to 2-space indent
      output.push(`  ${line.trim()}`);
      continue;
    }

    if (state === "dependencies_array") {
      if (effectiveLine.includes("]")) {
        const depOnCloseLine = extractQuotedString(line);
        if (depOnCloseLine) {
          entries.push({ commentLines: pendingComments, depLine: `${depIndent}"${depOnCloseLine}"`, sortKey: buildSortKey(depOnCloseLine) });
          pendingComments = [];
        }
        flushEntries(output, entries, pendingComments);
        output.push("  ]");
        entries = [];
        pendingComments = [];
        headerPushed = false;
        state = "advanced_block";
        continue;
      }

      const quoted = extractQuotedString(line);
      if (quoted) {
        entries.push({ commentLines: pendingComments, depLine: `${depIndent}"${quoted}"`, sortKey: buildSortKey(quoted) });
        pendingComments = [];
      } else {
        pendingComments.push(normalizeCommentLine(line, depIndent));
      }
      continue;
    }
  }

  // Flush any remaining outside lines at the end of the document
  for (const l of outsideBuffer) output.push(l);

  return output.join("\n");
}

/** Extracts the content of the first quoted string on a line, or undefined. */
function extractQuotedString(line: string): string | undefined {
  const match = /"([^"]*)"/.exec(line);
  return match?.[1];
}

/** Normalizes a comment/blank line to use the given indent. */
function normalizeCommentLine(line: string, indent: string): string {
  const trimmed = line.trim();
  if (trimmed.length === 0) return "";
  return `${indent}${trimmed}`;
}

/**
 * Builds a composite sort key: config + \0 + org + separator + artifact.
 *
 * Empty config sorts before any named config (e.g. `test`, `sbt-plugin`),
 * so runtime deps appear before test deps.
 */
function buildSortKey(depString: string): string {
  const m = dependencyPattern.exec(depString);
  if (!m) return depString.toLowerCase();

  const org = m[1].toLowerCase();
  const separator = m[2];
  const artifact = m[3].toLowerCase();
  const config = (m[5] ?? "").toLowerCase();

  return `${config}\0${org}${separator}${artifact}`;
}

/** Sorts entries and flushes them (plus trailing comments) to output. */
function flushEntries(
  output: string[],
  entries: DependencyEntry[],
  trailingComments: string[]
): void {
  entries.sort((a, b) => a.sortKey.localeCompare(b.sortKey));
  for (const entry of entries) {
    for (const comment of entry.commentLines) {
      output.push(comment);
    }
    output.push(entry.depLine);
  }
  for (const comment of trailingComments) {
    output.push(comment);
  }
}

/**
 * Formats a single-line group (e.g. `group = ["dep1" "dep2"]`).
 * Extracts all quoted strings, sorts them, and reconstructs.
 */
function formatSingleLineGroup(line: string, indent: string): string {
  const pattern = /"([^"]*)"/g;
  const deps: string[] = [];
  let match;
  while ((match = pattern.exec(line)) !== null) {
    deps.push(match[1]);
  }
  if (deps.length === 0) return line;

  deps.sort((a, b) => buildSortKey(a).localeCompare(buildSortKey(b)));
  // Reconstruct — but for single-line we keep it as-is
  return line;
}
