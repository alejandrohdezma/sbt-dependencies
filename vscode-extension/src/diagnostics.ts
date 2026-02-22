export interface DiagnosticResult {
  message: string;
  severity: "error" | "warning";
  source: "sbt-dependencies";
  range: { startLine: number; startCol: number; endLine: number; endCol: number };
}

/** Structural regexes for detecting dependency array contexts. */
const simpleGroupStart = /^\s*[\w][\w.-]*\s*=\s*\[/;
const advancedGroupStart = /^\s*[\w][\w.-]*\s*\{/;
const dependenciesArrayStart = /^\s*dependencies\s*=\s*\[/;

/** Matches a single-line object entry: `{ dependency = "...", note = "..." }` */
const singleLineObjectPattern = /\{[^}]*\}/g;

/** Extracts the `dependency` field value from an object entry string. */
const objectDepFieldPattern = /dependency\s*=\s*"([^"]*)"/;

/** Checks for the presence of a `note` field in an object entry string. */
const objectNoteFieldPattern = /note\s*=\s*"/;

/** Checks for the presence of an `intransitive = true` field in an object entry string. */
const objectIntransitiveFieldPattern = /intransitive\s*=\s*true/;

/** Regex mirroring Scala-side `Dependency.dependencyRegex`. */
const dependencyValidationPattern =
  /^\s*([^\s:]+)\s*(::?)\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*)?)?$/;

type ParserState = "outside" | "simple_array" | "advanced_block" | "dependencies_array" | "dependency_object";

/**
 * Extracts a dependency key (`org + separator + artifact`) from a dependency
 * string, or `undefined` if the string doesn't match the pattern.
 */
function extractDepKey(content: string): string | undefined {
  const m = dependencyValidationPattern.exec(content);
  if (!m) return undefined;
  return m[1] + m[2] + m[3];
}

/**
 * Validates a dependency string extracted from a dependency array.
 *
 * Returns a `DiagnosticResult` if the string is malformed, or `undefined` if valid.
 */
export function validateDependencyString(
  content: string,
  lineIndex: number,
  startCol: number
): DiagnosticResult | undefined {
  const range = { startLine: lineIndex, startCol, endLine: lineIndex, endCol: startCol + content.length };

  if (content.length === 0) {
    return { message: "Empty dependency string", severity: "error", source: "sbt-dependencies", range };
  }

  if (content.includes("{{") && !content.includes("}}")) {
    return { message: 'Unclosed variable reference: missing "}}"', severity: "error", source: "sbt-dependencies", range };
  }

  const m = dependencyValidationPattern.exec(content);

  if (!m) {
    return { message: 'Malformed dependency: expected format "org:artifact" or "org::artifact"', severity: "error", source: "sbt-dependencies", range };
  }

  const version = m[4];
  if (!version) {
    return { message: 'Missing version: expected format "org:artifact:version"', severity: "error", source: "sbt-dependencies", range };
  }
  if (!/^\{\{.*\}\}$/.test(version)) {
    const first = version[0];
    if (!/[\d=^~]/.test(first)) {
      return { message: `Invalid version marker "${first}": expected "=", "^", or "~"`, severity: "error", source: "sbt-dependencies", range };
    }
  }

  return undefined;
}

/**
 * Validates a single-line object entry `{ dependency = "...", note = "..." }`.
 *
 * Returns diagnostics for missing fields or invalid dependency values.
 */
function validateObjectEntry(
  objectText: string,
  lineIndex: number,
  objectStartCol: number
): { diagnostics: DiagnosticResult[]; depKey: string | undefined } {
  const diagnostics: DiagnosticResult[] = [];
  let depKey: string | undefined;

  const depMatch = objectDepFieldPattern.exec(objectText);
  const hasNote = objectNoteFieldPattern.test(objectText);
  const hasIntransitive = objectIntransitiveFieldPattern.test(objectText);

  if (!depMatch) {
    diagnostics.push({
      message: "Object entry must have a 'dependency' field",
      severity: "error",
      source: "sbt-dependencies",
      range: { startLine: lineIndex, startCol: objectStartCol, endLine: lineIndex, endCol: objectStartCol + objectText.length },
    });
    return { diagnostics, depKey };
  }

  if (!hasNote && !hasIntransitive) {
    diagnostics.push({
      message: "Object entry must have a 'note' or 'intransitive' field",
      severity: "error",
      source: "sbt-dependencies",
      range: { startLine: lineIndex, startCol: objectStartCol, endLine: lineIndex, endCol: objectStartCol + objectText.length },
    });
    return { diagnostics, depKey };
  }

  const depContent = depMatch[1];
  const depStartCol = objectStartCol + depMatch.index + depMatch[0].indexOf('"') + 1;
  const diag = validateDependencyString(depContent, lineIndex, depStartCol);
  if (diag) {
    diagnostics.push(diag);
  } else {
    depKey = extractDepKey(depContent);
  }

  return { diagnostics, depKey };
}

/**
 * Scans lines from a `dependencies.conf` file for malformed dependency strings
 * and returns diagnostic results.
 *
 * Uses a line-based state machine to only validate strings inside dependency
 * arrays (simple-group `= [...]` or advanced-group `dependencies = [...]`).
 *
 * Supports both plain string entries and object entries with `dependency` and `note` fields.
 */
export function parseDiagnostics(lines: string[]): DiagnosticResult[] {
  const diagnostics: DiagnosticResult[] = [];
  let state: ParserState = "outside";
  /** State to return to after a multi-line dependency object closes. */
  let preObjectState: "simple_array" | "dependencies_array" = "simple_array";
  let inBlockComment = false;
  let seenInGroup = new Map<string, number>();
  /** Tracks whether a multi-line object has a `dependency` field. */
  let objectHasDep = false;
  /** Tracks whether a multi-line object has a `note` field. */
  let objectHasNote = false;
  /** Tracks whether a multi-line object has an `intransitive = true` field. */
  let objectHasIntransitive = false;
  /** Start line of the current multi-line object. */
  let objectStartLine = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // Track block comments
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

    // Skip line comments
    const commentIdx = Math.min(
      effectiveLine.indexOf("//") === -1 ? Infinity : effectiveLine.indexOf("//"),
      effectiveLine.indexOf("#") === -1 ? Infinity : effectiveLine.indexOf("#")
    );
    if (commentIdx !== Infinity) {
      effectiveLine = effectiveLine.slice(0, commentIdx);
    }

    // State transitions — save previous state so closing-bracket lines are still validated
    const prevState: ParserState = state;

    if (state === "outside") {
      if (simpleGroupStart.test(effectiveLine)) {
        seenInGroup = new Map();
        state = effectiveLine.includes("]") ? "outside" : "simple_array";
      } else if (advancedGroupStart.test(effectiveLine)) {
        seenInGroup = new Map();
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
      // Inside a multi-line object — check for dependency/note fields
      if (objectDepFieldPattern.test(effectiveLine)) {
        objectHasDep = true;
        // Validate the dependency value
        const depMatch = objectDepFieldPattern.exec(line);
        if (depMatch) {
          const depContent = depMatch[1];
          const depStartCol = depMatch.index + depMatch[0].indexOf('"') + 1;
          const diag = validateDependencyString(depContent, i, depStartCol);
          if (diag) {
            diagnostics.push(diag);
          } else {
            const key = extractDepKey(depContent);
            if (key) {
              if (seenInGroup.has(key)) {
                diagnostics.push({
                  message: "Duplicate dependency in group",
                  severity: "warning",
                  source: "sbt-dependencies",
                  range: { startLine: i, startCol: depStartCol, endLine: i, endCol: depStartCol + depContent.length },
                });
              } else {
                seenInGroup.set(key, i);
              }
            }
          }
        }
      }
      if (objectNoteFieldPattern.test(effectiveLine)) {
        objectHasNote = true;
      }
      if (objectIntransitiveFieldPattern.test(effectiveLine)) {
        objectHasIntransitive = true;
      }
      if (effectiveLine.includes("}")) {
        if (!objectHasDep) {
          diagnostics.push({
            message: "Object entry must have a 'dependency' field",
            severity: "error",
            source: "sbt-dependencies",
            range: { startLine: objectStartLine, startCol: 0, endLine: i, endCol: line.length },
          });
        } else if (!objectHasNote && !objectHasIntransitive) {
          diagnostics.push({
            message: "Object entry must have a 'note' or 'intransitive' field",
            severity: "error",
            source: "sbt-dependencies",
            range: { startLine: objectStartLine, startCol: 0, endLine: i, endCol: line.length },
          });
        }
        state = preObjectState;
      }
      continue;
    }

    // Validate in dependency contexts (including closing-bracket lines)
    const inArrayContext = (s: ParserState) => s === "simple_array" || s === "dependencies_array";
    const validateState: ParserState = inArrayContext(prevState) ? prevState : state;
    if (validateState === "simple_array" || validateState === "dependencies_array") {
      // Check for single-line object entries first
      const lineWithoutObjects = processObjectEntries(line, effectiveLine, i, diagnostics, seenInGroup);

      // Check for multi-line object start (opening brace without closing)
      if (effectiveLine.includes("{") && !effectiveLine.includes("}")) {
        preObjectState = validateState;
        objectHasDep = false;
        objectHasNote = false;
        objectHasIntransitive = false;
        objectStartLine = i;
        // Check if this line already contains a dependency, note, or intransitive field
        if (objectDepFieldPattern.test(effectiveLine)) objectHasDep = true;
        if (objectNoteFieldPattern.test(effectiveLine)) objectHasNote = true;
        if (objectIntransitiveFieldPattern.test(effectiveLine)) objectHasIntransitive = true;
        state = "dependency_object";
        continue;
      }

      // Validate remaining plain string entries (not inside objects)
      const stringPattern = /"([^"]*)"/g;
      let strMatch;
      while ((strMatch = stringPattern.exec(lineWithoutObjects)) !== null) {
        const content = strMatch[1];
        const startCol = strMatch.index + 1; // skip opening quote
        const diag = validateDependencyString(content, i, startCol);
        if (diag) {
          diagnostics.push(diag);
        } else {
          const key = extractDepKey(content);
          if (key) {
            if (seenInGroup.has(key)) {
              diagnostics.push({
                message: "Duplicate dependency in group",
                severity: "warning",
                source: "sbt-dependencies",
                range: { startLine: i, startCol, endLine: i, endCol: startCol + content.length },
              });
            } else {
              seenInGroup.set(key, i);
            }
          }
        }
      }
    }
  }

  return diagnostics;
}

/**
 * Processes single-line object entries on a line, validates them, and returns the line
 * with object entries replaced by spaces (so they don't get picked up by the plain string scanner).
 */
function processObjectEntries(
  line: string,
  effectiveLine: string,
  lineIndex: number,
  diagnostics: DiagnosticResult[],
  seenInGroup: Map<string, number>
): string {
  let result = line;
  singleLineObjectPattern.lastIndex = 0;
  let objMatch;

  while ((objMatch = singleLineObjectPattern.exec(effectiveLine)) !== null) {
    const objectText = objMatch[0];
    const objectStartCol = objMatch.index;

    // Skip {…} matches inside quoted strings (e.g. "org::art:{{var}}")
    const quotesBefore = (effectiveLine.substring(0, objectStartCol).match(/"/g) || []).length;
    if (quotesBefore % 2 === 1) continue;

    const { diagnostics: objDiags, depKey } = validateObjectEntry(objectText, lineIndex, objectStartCol);
    diagnostics.push(...objDiags);

    if (depKey) {
      if (seenInGroup.has(depKey)) {
        // Find the dependency start position for a more precise range
        const depMatch = objectDepFieldPattern.exec(objectText);
        if (depMatch) {
          const depContent = depMatch[1];
          const depStartCol = objectStartCol + depMatch.index + depMatch[0].indexOf('"') + 1;
          diagnostics.push({
            message: "Duplicate dependency in group",
            severity: "warning",
            source: "sbt-dependencies",
            range: { startLine: lineIndex, startCol: depStartCol, endLine: lineIndex, endCol: depStartCol + depContent.length },
          });
        }
      } else {
        seenInGroup.set(depKey, lineIndex);
      }
    }

    // Replace the object entry in result so plain string scanner skips it
    result = result.substring(0, objMatch.index) + " ".repeat(objectText.length) + result.substring(objMatch.index + objectText.length);
  }

  return result;
}
