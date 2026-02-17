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

/** Regex mirroring Scala-side `Dependency.dependencyRegex`. */
const dependencyValidationPattern =
  /^\s*([^\s:]+)\s*(::?)\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*)?)?$/;

type ParserState = "outside" | "simple_array" | "advanced_block" | "dependencies_array";

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
 * Scans lines from a `dependencies.conf` file for malformed dependency strings
 * and returns diagnostic results.
 *
 * Uses a line-based state machine to only validate strings inside dependency
 * arrays (simple-group `= [...]` or advanced-group `dependencies = [...]`).
 */
export function parseDiagnostics(lines: string[]): DiagnosticResult[] {
  const diagnostics: DiagnosticResult[] = [];
  let state: ParserState = "outside";
  let inBlockComment = false;
  let seenInGroup = new Map<string, number>();

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
    const prevState = state;

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
    }

    // Validate in dependency contexts (including closing-bracket lines)
    const validateState = (prevState === "simple_array" || prevState === "dependencies_array") ? prevState : state;
    if (validateState === "simple_array" || validateState === "dependencies_array") {
      const stringPattern = /"([^"]*)"/g;
      let strMatch;
      while ((strMatch = stringPattern.exec(line)) !== null) {
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
