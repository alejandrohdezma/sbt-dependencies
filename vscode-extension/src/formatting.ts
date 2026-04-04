import { walkDocument, objectDepFieldPattern, objectIntransitiveFieldPattern, objectScalaFilterFieldPattern } from "./parser";

/** Regex mirroring Scala-side `Dependency.dependencyRegex`. */
const dependencyPattern =
  /^\s*([^\s:]+)\s*(::?)\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*)?)?$/;

/** Max line length for single-line object entries. */
const maxObjectLineLength = 120;

/** Matches SBT-style dependency: "org" %% "art" % "ver" [% "config" | % Test] */
export const sbtDependencyPattern =
  /^\s*(?:(libraryDependencies\s*\+[+=]|addSbtPlugin\s*\()\s*)?"([^"]+)"\s*(%{1,2})\s*"([^"]+)"\s*%\s*(?:"([^"]+)"|(\w+))(?:\s*%\s*(?:"([^"]+)"|(\w+)))?\s*\)?\s*,?\s*$/;

/** A dependency entry ready for sorting and output. */
interface DependencyEntry {
  depLine: string;
  /** Composite sort key: config + \0 + org:artifact (lowercased). */
  sortKey: string;
}

/** Ordering for group names: `sbt-build` always comes first, then alphabetically. */
function groupSortKey(name: string): string {
  return name === "sbt-build" ? `\0${name}` : name;
}

/**
 * Formats a `dependencies.conf` document by sorting groups (`sbt-build`
 * first, then alphabetically) and dependencies within each group.
 *
 * - Simple groups: 2-space indent for dependencies
 * - Advanced blocks: 2-space indent for fields, 4-space indent for
 *   dependencies inside `dependencies = [...]`
 * - All comments are stripped (the SBT plugin never writes them back)
 * - Object entries (`{ dependency = "...", note = "..." }`) are preserved
 */
export function formatDocument(lines: string[]): string {
  const groups: { name: string; lines: string[] }[] = [];
  let currentGroup: { name: string; lines: string[] } | undefined;
  let depIndent = "  ";
  let entries: DependencyEntry[] = [];
  let inDepsArray = false;

  let objectLines: string[] = [];
  let objectDepString: string | undefined;
  let inObject = false;

  /** Tracks lines already handled as SBT conversions to skip duplicate events. */
  let sbtConvertedLine = -1;

  const output = { push(line: string) { currentGroup!.lines.push(line); } };

  for (const event of walkDocument(lines)) {
    switch (event.type) {
      case "group-start": {
        currentGroup = { name: event.name, lines: [] };
        groups.push(currentGroup);

        if (event.groupKind === "simple") {
          depIndent = "  ";
          if (event.singleLine) {
            output.push(formatSingleLineGroup(event.rawLine, depIndent));
          } else {
            output.push(event.rawLine);
            entries = [];
            inDepsArray = true;
          }
        } else {
          depIndent = "    ";
          output.push(event.rawLine);
          inDepsArray = false;
        }
        break;
      }
      case "setting-line": {
        output.push(`  ${event.rawLine.trim()}`);
        break;
      }
      case "dependency-string": {
        if (!inDepsArray && event.arrayKind === "dependencies") {
          // First dep in advanced block's dependencies array
          output.push(`  dependencies = [`);
          entries = [];
          inDepsArray = true;
        }
        // SBT lines have multiple quoted strings; only process once per line
        if (event.lineIndex === sbtConvertedLine) break;
        const sbtDep = convertSbtDependency(event.rawLine);
        if (sbtDep) {
          sbtConvertedLine = event.lineIndex;
          entries.push({ depLine: `${depIndent}"${sbtDep}"`, sortKey: buildSortKey(sbtDep) });
        } else {
          if (event.content.length > 0) {
            entries.push({ depLine: `${depIndent}"${event.content}"`, sortKey: buildSortKey(event.content) });
          }
        }
        break;
      }
      case "single-line-object": {
        if (!inDepsArray && event.arrayKind === "dependencies") {
          output.push(`  dependencies = [`);
          entries = [];
          inDepsArray = true;
        }
        const entry = extractDependencyEntryFromObject(event.objectText, depIndent);
        if (entry) entries.push(entry);
        break;
      }
      case "multi-line-object-start": {
        if (!inDepsArray && event.arrayKind === "dependencies") {
          output.push(`  dependencies = [`);
          entries = [];
          inDepsArray = true;
        }
        objectLines = [event.rawLine];
        objectDepString = undefined;
        const depMatch = objectDepFieldPattern.exec(event.rawLine);
        if (depMatch) objectDepString = depMatch[1];
        inObject = true;
        break;
      }
      case "multi-line-object-field": {
        if (inObject && objectLines.length > 0 && objectLines[objectLines.length - 1] !== event.rawLine) {
          objectLines.push(event.rawLine);
        }
        const depMatch = objectDepFieldPattern.exec(event.rawLine);
        if (depMatch) objectDepString = depMatch[1];
        break;
      }
      case "multi-line-object-end": {
        if (inObject && objectLines.length > 0 && objectLines[objectLines.length - 1] !== event.rawLine) {
          objectLines.push(event.rawLine);
        }
        const entry = buildObjectEntry(objectLines, depIndent, objectDepString);
        entries.push(entry);
        objectLines = [];
        objectDepString = undefined;
        inObject = false;
        break;
      }
      case "group-end": {
        if (inDepsArray) {
          flushEntries(output, entries);
          if (event.groupKind === "simple") {
            output.push("]");
          } else {
            output.push("  ]");
          }
          entries = [];
          inDepsArray = false;
        }
        if (event.groupKind === "advanced") {
          output.push(event.rawLine);
        }
        break;
      }
    }
  }

  groups.sort((a, b) => {
    const ka = groupSortKey(a.name);
    const kb = groupSortKey(b.name);
    return ka < kb ? -1 : ka > kb ? 1 : 0;
  });

  return groups.map(g => g.lines.join("\n")).join("\n\n") + "\n";
}

/**
 * Extracts a dependency entry from a single-line object text.
 */
function extractDependencyEntryFromObject(
  objectText: string,
  indent: string
): DependencyEntry | undefined {
  const depMatch = objectDepFieldPattern.exec(objectText);
  if (!depMatch) return undefined;

  const depString = depMatch[1];
  const noteMatch = /note\s*=\s*"([^"]*)"/.exec(objectText);
  const note = noteMatch?.[1];
  const isIntransitive = objectIntransitiveFieldPattern.test(objectText);
  const scalaFilterMatch = objectScalaFilterFieldPattern.exec(objectText);
  const scalaFilter = scalaFilterMatch?.[1];

  if (note || isIntransitive || scalaFilter) {
    return formatObjectFields(depString, note, isIntransitive, scalaFilter, indent);
  } else {
    return { depLine: `${indent}${objectText.trim()}`, sortKey: buildSortKey(depString) };
  }
}

/**
 * Builds a DependencyEntry from accumulated multi-line object lines.
 */
function buildObjectEntry(
  objectLines: string[],
  indent: string,
  depString: string | undefined
): DependencyEntry {
  if (!depString) {
    return {
      depLine: objectLines.map(l => `${indent}${l.trim()}`).join("\n"),
      sortKey: "",
    };
  }

  let note: string | undefined;
  let isIntransitive = false;
  let scalaFilter: string | undefined;
  for (const l of objectLines) {
    const noteMatch = /note\s*=\s*"([^"]*)"/.exec(l);
    if (noteMatch) note = noteMatch[1];
    if (objectIntransitiveFieldPattern.test(l)) isIntransitive = true;
    const scalaFilterMatch = objectScalaFilterFieldPattern.exec(l);
    if (scalaFilterMatch) scalaFilter = scalaFilterMatch[1];
  }

  if (note || isIntransitive || scalaFilter) {
    return formatObjectFields(depString, note, isIntransitive, scalaFilter, indent);
  }

  return {
    depLine: objectLines.map(l => `${indent}${l.trim()}`).join("\n"),
    sortKey: buildSortKey(depString),
  };
}

/**
 * Formats an object entry with dependency, optional note, and optional intransitive fields.
 * Uses single-line format if it fits within the threshold, multi-line otherwise.
 */
function formatObjectFields(
  depString: string,
  note: string | undefined,
  isIntransitive: boolean,
  scalaFilter: string | undefined,
  indent: string
): DependencyEntry {
  const noteField = note ? `note = "${note}"` : undefined;
  const intransitiveField = isIntransitive ? "intransitive = true" : undefined;
  const scalaFilterField = scalaFilter ? `scala-filter = "${scalaFilter}"` : undefined;
  const fields = [noteField, intransitiveField, scalaFilterField].filter(Boolean).join(", ");

  const singleLine = `${indent}{ dependency = "${depString}", ${fields} }`;
  if (singleLine.length <= maxObjectLineLength) {
    return { depLine: singleLine, sortKey: buildSortKey(depString) };
  } else {
    const noteSection = note ? `\n${indent}  note = "${note}"` : "";
    const intransitiveSection = isIntransitive ? `\n${indent}  intransitive = true` : "";
    const scalaFilterSection = scalaFilter ? `\n${indent}  scala-filter = "${scalaFilter}"` : "";
    return {
      depLine: `${indent}{\n${indent}  dependency = "${depString}"${noteSection}${intransitiveSection}${scalaFilterSection}\n${indent}}`,
      sortKey: buildSortKey(depString),
    };
  }
}

/** Converts an SBT-style dependency line to the canonical HOCON format, or undefined. */
export function convertSbtDependency(line: string): string | undefined {
  const m = sbtDependencyPattern.exec(line);
  if (!m) return undefined;
  const prefix = m[1];
  const org = m[2];
  const sep = m[3] === "%%" ? "::" : ":";
  let artifact = m[4];
  const version = m[5] ?? `{{${m[6]}}}`;
  let config = m[7] ?? (m[8] ? m[8].toLowerCase() : undefined);

  // Artifact ending with _2.12_1.0 indicates an sbt plugin
  if (artifact.endsWith("_2.12_1.0")) {
    artifact = artifact.slice(0, -"_2.12_1.0".length);
    config = "sbt-plugin";
  }

  // addSbtPlugin always implies sbt-plugin config
  if (prefix?.startsWith("addSbtPlugin")) {
    config = "sbt-plugin";
  }

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

  return `${config}\0${org}\0${artifact}`;
}

/** Sorts entries and flushes them to output. */
function flushEntries(
  output: { push(line: string): void },
  entries: DependencyEntry[]
): void {
  entries.sort((a, b) => a.sortKey < b.sortKey ? -1 : a.sortKey > b.sortKey ? 1 : 0);
  for (const entry of entries) {
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

  deps.sort((a, b) => { const ka = buildSortKey(a), kb = buildSortKey(b); return ka < kb ? -1 : ka > kb ? 1 : 0; });
  return line;
}
