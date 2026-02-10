import { dependencyPattern } from "./hover";

export interface ReferenceLocation {
  line: number;
  startCol: number;
  endCol: number;
}

const variablePattern = /\{\{(\w+)\}\}/g;

/**
 * Finds all references of the entity (variable or dependency) under the
 * cursor at the given line and column.
 *
 * - Variables (`{{varName}}`): returns all occurrences of the same variable
 *   across all lines, with ranges spanning the full `{{...}}` token.
 * - Dependencies (`org::artifact`): returns all occurrences of the same
 *   org + separator + artifact, with ranges spanning only the
 *   `org::artifact` portion.
 *
 * Returns `undefined` if no entity is found under the cursor.
 */
export function findReferences(
  lines: string[],
  line: number,
  column: number
): ReferenceLocation[] | undefined {
  if (line < 0 || line >= lines.length) return undefined;

  const currentLine = lines[line];

  // Try variable first
  variablePattern.lastIndex = 0;
  let varMatch: RegExpExecArray | null;
  while ((varMatch = variablePattern.exec(currentLine)) !== null) {
    const matchStart = varMatch.index;
    const matchEnd = varMatch.index + varMatch[0].length;
    if (column >= matchStart && column < matchEnd) {
      const varName = varMatch[1];
      return collectVariableReferences(lines, varName);
    }
  }

  // Try dependency
  dependencyPattern.lastIndex = 0;
  let depMatch: RegExpExecArray | null;
  while ((depMatch = dependencyPattern.exec(currentLine)) !== null) {
    const matchStart = depMatch.index;
    const matchEnd = depMatch.index + depMatch[0].length;
    if (column >= matchStart && column < matchEnd) {
      const key = depMatch[1] + depMatch[2] + depMatch[3];
      return collectDependencyReferences(lines, key);
    }
  }

  return undefined;
}

function collectVariableReferences(
  lines: string[],
  varName: string
): ReferenceLocation[] | undefined {
  const results: ReferenceLocation[] = [];
  const pattern = new RegExp(`\\{\\{${varName}\\}\\}`, "g");

  for (let i = 0; i < lines.length; i++) {
    pattern.lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = pattern.exec(lines[i])) !== null) {
      results.push({
        line: i,
        startCol: match.index,
        endCol: match.index + match[0].length,
      });
    }
  }

  return results.length > 0 ? results : undefined;
}

function collectDependencyReferences(
  lines: string[],
  key: string
): ReferenceLocation[] | undefined {
  const results: ReferenceLocation[] = [];

  for (let i = 0; i < lines.length; i++) {
    dependencyPattern.lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = dependencyPattern.exec(lines[i])) !== null) {
      const matchKey = match[1] + match[2] + match[3];
      if (matchKey === key) {
        const depPortionLength = match[1].length + match[2].length + match[3].length;
        results.push({
          line: i,
          startCol: match.index,
          endCol: match.index + depPortionLength,
        });
      }
    }
  }

  return results.length > 0 ? results : undefined;
}
