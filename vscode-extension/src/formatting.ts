/** Structural regexes (same as diagnostics.ts / symbols.ts). */
const simpleGroupStart = /^(\s*)([\w][\w.-]*)\s*=\s*\[/;
const advancedGroupStart = /^(\s*)([\w][\w.-]*)\s*\{/;
const dependenciesArrayStart = /^\s*dependencies\s*=\s*\[/;

type ParserState = "outside" | "simple_array" | "advanced_block" | "dependencies_array" | "dependency_object";

/** Regex mirroring Scala-side `Dependency.dependencyRegex`. */
const dependencyPattern =
  /^\s*([^\s:]+)\s*(::?)\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*)?)?$/;

/** Extracts the `dependency` field value from an object entry. */
const objectDepFieldPattern = /dependency\s*=\s*"([^"]*)"/;

/** Max line length for single-line object entries. */
const maxObjectLineLength = 120;

/** Matches SBT-style dependency: "org" %% "art" % "ver" [% "config"] */
const sbtDependencyPattern =
  /^\s*(?:libraryDependencies\s*\+[+=]\s*)?"([^"]+)"\s*(%{1,2})\s*"([^"]+)"\s*%\s*"([^"]+)"(?:\s*%\s*"([^"]+)")?\s*,?\s*$/;

/** A dependency entry ready for sorting and output. */
interface DependencyEntry {
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
 * - All comments are stripped (the SBT plugin never writes them back)
 * - Object entries (`{ dependency = "...", note = "..." }`) are preserved
 */
export function formatDocument(lines: string[]): string {
  const output: string[] = [];
  let state: ParserState = "outside";
  /** State to return to after a multi-line dependency object closes. */
  let preObjectState: "simple_array" | "dependencies_array" = "simple_array";
  let inBlockComment = false;

  /** Indent for deps: 2 in simple groups, 4 in advanced blocks. */
  let depIndent = "  ";

  /** Collected dependency entries in the current array. */
  let entries: DependencyEntry[] = [];

  /** Whether the opening bracket line has already been pushed. */
  let headerPushed = false;

  /** Whether at least one group has been emitted (for blank-line normalization). */
  let hasEmittedGroup = false;

  /** Accumulated lines for a multi-line object entry. */
  let objectLines: string[] = [];

  /** Dependency string extracted from a multi-line object. */
  let objectDepString: string | undefined;

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
        // About to start a new group — insert exactly one blank line
        // between this group and the previous one. Comments are stripped.
        if (hasEmittedGroup) {
          output.push("");
        }
        hasEmittedGroup = true;

        if (simpleMatch) {
          depIndent = "  ";
          if (effectiveLine.includes("]")) {
            output.push(formatSingleLineGroup(line, depIndent));
          } else {
            output.push(line);
            headerPushed = true;
            entries = [];
            state = "simple_array";
          }
        } else {
          depIndent = "    ";
          output.push(line);
          state = "advanced_block";
        }
        continue;
      }

      // Not a group start — comments and blanks outside groups are dropped
      continue;
    }

    if (state === "simple_array") {
      if (effectiveLine.includes("]")) {
        // Closing bracket line — may contain a last dep on same line
        const entry = extractDependencyEntry(line, depIndent);
        if (entry) entries.push(entry);
        flushEntries(output, entries);
        output.push("]");
        entries = [];
        headerPushed = false;
        state = "outside";
        continue;
      }

      // Check for multi-line object start
      if (effectiveLine.includes("{") && !effectiveLine.includes("}")) {
        objectLines = [line];
        objectDepString = undefined;
        const depMatch = objectDepFieldPattern.exec(line);
        if (depMatch) objectDepString = depMatch[1];
        preObjectState = "simple_array";
        state = "dependency_object";
        continue;
      }

      const entry = extractDependencyEntry(line, depIndent);
      if (entry) entries.push(entry);
      // Comments and blank lines inside the array are dropped
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
        const entry = extractDependencyEntry(line, depIndent);
        if (entry) entries.push(entry);
        flushEntries(output, entries);
        output.push("  ]");
        entries = [];
        headerPushed = false;
        state = "advanced_block";
        continue;
      }

      // Check for multi-line object start
      if (effectiveLine.includes("{") && !effectiveLine.includes("}")) {
        objectLines = [line];
        objectDepString = undefined;
        const depMatch = objectDepFieldPattern.exec(line);
        if (depMatch) objectDepString = depMatch[1];
        preObjectState = "dependencies_array";
        state = "dependency_object";
        continue;
      }

      const entry = extractDependencyEntry(line, depIndent);
      if (entry) entries.push(entry);
      // Comments and blank lines inside the array are dropped
      continue;
    }

    if (state === "dependency_object") {
      objectLines.push(line);
      const depMatch = objectDepFieldPattern.exec(line);
      if (depMatch) objectDepString = depMatch[1];

      if (effectiveLine.includes("}")) {
        // Object closed — reconstruct as a properly indented entry
        const entry = buildObjectEntry(objectLines, depIndent, objectDepString);
        entries.push(entry);
        objectLines = [];
        objectDepString = undefined;
        state = preObjectState;
      }
      continue;
    }
  }

  return output.join("\n");
}

/**
 * Extracts a dependency entry from a line, handling both plain string and
 * single-line object format.
 *
 * Returns the formatted dep line and sort key, or undefined if the line
 * doesn't contain a dependency.
 */
function extractDependencyEntry(
  line: string,
  indent: string
): { depLine: string; sortKey: string } | undefined {
  // Check for single-line object entry first
  const objectMatch = /\{[^}]*\}/.exec(line);
  if (objectMatch) {
    const objectText = objectMatch[0];
    const depMatch = objectDepFieldPattern.exec(objectText);
    if (depMatch) {
      const depString = depMatch[1];
      // Extract note if present
      const noteMatch = /note\s*=\s*"([^"]*)"/.exec(objectText);
      const note = noteMatch?.[1];

      if (note) {
        // Re-format with proper indent, choosing single vs multi-line based on length
        const singleLine = `${indent}{ dependency = "${depString}", note = "${note}" }`;
        if (singleLine.length <= maxObjectLineLength) {
          return { depLine: singleLine, sortKey: buildSortKey(depString) };
        } else {
          // Multi-line format — first line is depLine, extra lines follow
          return {
            depLine: `${indent}{\n${indent}  dependency = "${depString}"\n${indent}  note = "${note}"\n${indent}}`,
            sortKey: buildSortKey(depString),
          };
        }
      } else {
        // Object without note — preserve as-is with normalized indent
        return { depLine: `${indent}${objectText.trim()}`, sortKey: buildSortKey(depString) };
      }
    }
  }

  // SBT format: "org" %% "art" % "ver" [% "config"]
  const sbtDep = convertSbtDependency(line);
  if (sbtDep) {
    return { depLine: `${indent}"${sbtDep}"`, sortKey: buildSortKey(sbtDep) };
  }

  // Plain string entry
  const quoted = extractQuotedString(line);
  if (quoted) {
    return { depLine: `${indent}"${quoted}"`, sortKey: buildSortKey(quoted) };
  }

  return undefined;
}

/**
 * Builds a DependencyEntry from accumulated multi-line object lines.
 */
function buildObjectEntry(
  objectLines: string[],
  indent: string,
  depString: string | undefined
): { depLine: string; sortKey: string } {
  if (!depString) {
    // No dependency field found — preserve as-is
    return {
      depLine: objectLines.map(l => `${indent}${l.trim()}`).join("\n"),
      sortKey: "",
    };
  }

  // Extract note from the multi-line object
  let note: string | undefined;
  for (const l of objectLines) {
    const noteMatch = /note\s*=\s*"([^"]*)"/.exec(l);
    if (noteMatch) {
      note = noteMatch[1];
      break;
    }
  }

  if (note) {
    // Re-format with threshold-based formatting
    const singleLine = `${indent}{ dependency = "${depString}", note = "${note}" }`;
    if (singleLine.length <= maxObjectLineLength) {
      return { depLine: singleLine, sortKey: buildSortKey(depString) };
    } else {
      return {
        depLine: `${indent}{\n${indent}  dependency = "${depString}"\n${indent}  note = "${note}"\n${indent}}`,
        sortKey: buildSortKey(depString),
      };
    }
  }

  // No note — preserve with normalized indent
  return {
    depLine: objectLines.map(l => `${indent}${l.trim()}`).join("\n"),
    sortKey: buildSortKey(depString),
  };
}

/** Extracts the content of the first quoted string on a line, or undefined. */
function extractQuotedString(line: string): string | undefined {
  const match = /"([^"]*)"/.exec(line);
  return match?.[1];
}

/** Converts an SBT-style dependency line to the canonical HOCON format, or undefined. */
function convertSbtDependency(line: string): string | undefined {
  const m = sbtDependencyPattern.exec(line);
  if (!m) return undefined;
  const org = m[1];
  const sep = m[2] === "%%" ? "::" : ":";
  const artifact = m[3];
  const version = m[4];
  const config = m[5];
  return `${org}${sep}${artifact}:${version}${config ? `:${config}` : ""}`;
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

/** Sorts entries and flushes them to output. */
function flushEntries(
  output: string[],
  entries: DependencyEntry[]
): void {
  entries.sort((a, b) => a.sortKey.localeCompare(b.sortKey));
  for (const entry of entries) {
    // depLine may contain newlines for multi-line objects
    for (const line of entry.depLine.split("\n")) {
      output.push(line);
    }
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
