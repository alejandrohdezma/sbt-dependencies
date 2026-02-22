import { parseDependency } from "./hover";

export interface DepCodeLensData {
  line: number;
  org: string;
  artifact: string;
  version: string;
  reason: "pinned" | "intransitive";
}

/** Structural regexes for detecting dependency array contexts. */
const simpleGroupStart = /^\s*[\w][\w.-]*\s*=\s*\[/;
const advancedGroupStart = /^\s*[\w][\w.-]*\s*\{/;
const dependenciesArrayStart = /^\s*dependencies\s*=\s*\[/;

/** Checks if a line is a single-line object entry (with or without note). */
const singleLineObjectPattern = /\{[^}]*\}/;

/** Extracts the `dependency` field value from an object entry. */
const objectDepFieldPattern = /dependency\s*=\s*"([^"]*)"/;

/** Checks for the presence of a `note` field. */
const objectNoteFieldPattern = /note\s*=\s*"/;

/** Checks for `intransitive = true`. */
const objectIntransitivePattern = /intransitive\s*=\s*true/;

type ParserState = "outside" | "simple_array" | "advanced_block" | "dependencies_array" | "dependency_object";

/**
 * Scans lines from a `dependencies.conf` file and returns CodeLens data
 * for pinned dependencies (those with `=`, `^`, or `~` version markers)
 * that do not have a note explaining the pin.
 *
 * Dependencies inside object entries with a `note` field are skipped.
 * Intransitive entries without a `note` produce a CodeLens suggesting
 * the user to document why the dependency is intransitive.
 */
export function parsePinnedWithoutNote(lines: string[]): DepCodeLensData[] {
  const results: DepCodeLensData[] = [];
  let state: ParserState = "outside";
  let preObjectState: "simple_array" | "dependencies_array" = "simple_array";
  let inBlockComment = false;

  /** Multi-line object tracking. */
  let objectHasNote = false;
  let objectHasIntransitive = false;
  let objectDepLine: string | undefined;
  let objectStartLine = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // Strip block/line comments
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

    // State transitions
    if (state === "outside") {
      if (simpleGroupStart.test(effectiveLine)) {
        state = effectiveLine.includes("]") ? "outside" : "simple_array";
      } else if (advancedGroupStart.test(effectiveLine)) {
        state = "advanced_block";
      }
    } else if (state === "advanced_block") {
      if (dependenciesArrayStart.test(effectiveLine)) {
        state = effectiveLine.includes("]") ? "advanced_block" : "dependencies_array";
      } else if (effectiveLine.includes("}")) {
        state = "outside";
      }
    } else if (state === "simple_array") {
      if (effectiveLine.includes("]")) {
        state = "outside";
      }
    } else if (state === "dependencies_array") {
      if (effectiveLine.includes("]")) {
        state = "advanced_block";
      }
    } else if (state === "dependency_object") {
      if (objectNoteFieldPattern.test(effectiveLine)) objectHasNote = true;
      if (objectIntransitivePattern.test(effectiveLine)) objectHasIntransitive = true;
      if (objectDepFieldPattern.test(effectiveLine)) objectDepLine = line;

      if (effectiveLine.includes("}")) {
        // Emit CodeLens for intransitive objects without a note
        if (objectHasIntransitive && !objectHasNote && objectDepLine) {
          const depMatch = objectDepFieldPattern.exec(objectDepLine);
          if (depMatch) {
            const dep = parseDependency(depMatch[1]);
            if (dep) {
              results.push({
                line: objectStartLine,
                org: dep.org,
                artifact: dep.artifact,
                version: dep.version ?? "",
                reason: "intransitive",
              });
            }
          }
        }
        state = preObjectState;
      }
      continue;
    }

    // Check for pinned deps in dependency array contexts
    if (state === "simple_array" || state === "dependencies_array") {
      // Check single-line object entries
      const singleLineObjMatch = singleLineObjectPattern.exec(effectiveLine);
      if (singleLineObjMatch) {
        const objectText = singleLineObjMatch[0];
        const hasNote = objectNoteFieldPattern.test(objectText);
        const hasIntransitive = objectIntransitivePattern.test(objectText);

        if (hasIntransitive && !hasNote) {
          // Intransitive without note — emit CodeLens
          const depMatch = objectDepFieldPattern.exec(objectText);
          if (depMatch) {
            const dep = parseDependency(depMatch[1]);
            if (dep) {
              results.push({
                line: i,
                org: dep.org,
                artifact: dep.artifact,
                version: dep.version ?? "",
                reason: "intransitive",
              });
            }
          }
        }
        continue;
      }

      // Check for multi-line object start
      if (effectiveLine.includes("{") && !effectiveLine.includes("}")) {
        preObjectState = state;
        objectHasNote = false;
        objectHasIntransitive = false;
        objectDepLine = undefined;
        objectStartLine = i;
        state = "dependency_object";
        continue;
      }

      const dep = parseDependency(line);
      if (dep && dep.version && /^[=^~]/.test(dep.version)) {
        results.push({
          line: i,
          org: dep.org,
          artifact: dep.artifact,
          version: dep.version,
          reason: "pinned",
        });
      }
    }
  }

  return results;
}
