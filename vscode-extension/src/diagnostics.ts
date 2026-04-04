import { walkDocument, objectDepFieldPattern, objectNoteFieldPattern, objectIntransitiveFieldPattern, objectScalaFilterFieldPattern } from "./parser";

export interface DiagnosticResult {
  message: string;
  severity: "error" | "warning";
  source: "sbt-dependencies";
  range: { startLine: number; startCol: number; endLine: number; endCol: number };
}

/** Regex mirroring Scala-side `Dependency.dependencyRegex`. */
const dependencyValidationPattern =
  /^\s*([^\s:]+)\s*(::?)\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*(?::\s*([^\s:]+)\s*)?)?$/;

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
  const hasScalaFilter = objectScalaFilterFieldPattern.test(objectText);

  if (!depMatch) {
    diagnostics.push({
      message: "Object entry must have a 'dependency' field",
      severity: "error",
      source: "sbt-dependencies",
      range: { startLine: lineIndex, startCol: objectStartCol, endLine: lineIndex, endCol: objectStartCol + objectText.length },
    });
    return { diagnostics, depKey };
  }

  if (!hasNote && !hasIntransitive && !hasScalaFilter) {
    diagnostics.push({
      message: "Object entry must have a 'note', 'intransitive', or 'scala-filter' field",
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
  let seenInGroup = new Map<string, number>();

  for (const event of walkDocument(lines)) {
    switch (event.type) {
      case "group-start": {
        seenInGroup = new Map();
        break;
      }
      case "dependency-string": {
        const diag = validateDependencyString(event.content, event.lineIndex, event.startCol);
        if (diag) {
          diagnostics.push(diag);
        } else {
          const key = extractDepKey(event.content);
          if (key) {
            if (seenInGroup.has(key)) {
              diagnostics.push({
                message: "Duplicate dependency in group",
                severity: "warning",
                source: "sbt-dependencies",
                range: { startLine: event.lineIndex, startCol: event.startCol, endLine: event.lineIndex, endCol: event.startCol + event.content.length },
              });
            } else {
              seenInGroup.set(key, event.lineIndex);
            }
          }
        }
        break;
      }
      case "single-line-object": {
        const { diagnostics: objDiags, depKey } = validateObjectEntry(event.objectText, event.lineIndex, event.objectStartCol);
        diagnostics.push(...objDiags);
        if (depKey) {
          if (seenInGroup.has(depKey)) {
            const depMatch = objectDepFieldPattern.exec(event.objectText);
            if (depMatch) {
              const depContent = depMatch[1];
              const depStartCol = event.objectStartCol + depMatch.index + depMatch[0].indexOf('"') + 1;
              diagnostics.push({
                message: "Duplicate dependency in group",
                severity: "warning",
                source: "sbt-dependencies",
                range: { startLine: event.lineIndex, startCol: depStartCol, endLine: event.lineIndex, endCol: depStartCol + depContent.length },
              });
            }
          } else {
            seenInGroup.set(depKey, event.lineIndex);
          }
        }
        break;
      }
      case "multi-line-object-field": {
        // Validate the dependency value on the line it appears
        if (event.field === "dependency" && event.fieldValue !== undefined && event.fieldValueStartCol !== undefined) {
          const diag = validateDependencyString(event.fieldValue, event.lineIndex, event.fieldValueStartCol);
          if (diag) {
            diagnostics.push(diag);
          } else {
            const key = extractDepKey(event.fieldValue);
            if (key) {
              if (seenInGroup.has(key)) {
                diagnostics.push({
                  message: "Duplicate dependency in group",
                  severity: "warning",
                  source: "sbt-dependencies",
                  range: { startLine: event.lineIndex, startCol: event.fieldValueStartCol, endLine: event.lineIndex, endCol: event.fieldValueStartCol + event.fieldValue.length },
                });
              } else {
                seenInGroup.set(key, event.lineIndex);
              }
            }
          }
        }
        break;
      }
      case "multi-line-object-end": {
        if (!event.hasDependency) {
          diagnostics.push({
            message: "Object entry must have a 'dependency' field",
            severity: "error",
            source: "sbt-dependencies",
            range: { startLine: event.objectStartLine, startCol: 0, endLine: event.lineIndex, endCol: event.rawLine.length },
          });
        } else if (!event.hasNote && !event.hasIntransitive && !event.hasScalaFilter) {
          diagnostics.push({
            message: "Object entry must have a 'note', 'intransitive', or 'scala-filter' field",
            severity: "error",
            source: "sbt-dependencies",
            range: { startLine: event.objectStartLine, startCol: 0, endLine: event.lineIndex, endCol: event.rawLine.length },
          });
        }
        break;
      }
    }
  }

  return diagnostics;
}
