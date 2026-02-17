import { findReferences } from "./references";

export interface RenameRange {
  startLine: number;
  startCol: number;
  endLine: number;
  endCol: number;
}

export interface RenameEdit {
  line: number;
  startCol: number;
  endCol: number;
  newText: string;
}

export interface RenameResult {
  edits: RenameEdit[];
}

const variablePattern = /\{\{(\w+)\}\}/g;

/**
 * Checks whether the cursor is on a `{{varName}}` token and returns
 * the range of just the variable name (inside the braces).
 */
export function prepareVariableRename(
  lines: string[],
  line: number,
  column: number
): RenameRange | undefined {
  if (line < 0 || line >= lines.length) return undefined;

  const currentLine = lines[line];
  variablePattern.lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = variablePattern.exec(currentLine)) !== null) {
    const matchStart = match.index;
    const matchEnd = match.index + match[0].length;
    if (column >= matchStart && column < matchEnd) {
      return {
        startLine: line,
        startCol: matchStart + 2,
        endLine: line,
        endCol: matchEnd - 2,
      };
    }
  }

  return undefined;
}

/**
 * Computes rename edits for all occurrences of the variable under the
 * cursor.  Strips `{{` / `}}` from `newName` if the user includes them.
 */
export function computeVariableRenameEdits(
  lines: string[],
  line: number,
  column: number,
  newName: string
): RenameResult | undefined {
  if (!prepareVariableRename(lines, line, column)) return undefined;

  const stripped = newName.replace(/^\{\{/, "").replace(/\}\}$/, "");

  const refs = findReferences(lines, line, column);
  if (!refs) return undefined;

  const edits: RenameEdit[] = refs.map((r) => ({
    line: r.line,
    startCol: r.startCol + 2,
    endCol: r.endCol - 2,
    newText: stripped,
  }));

  return { edits };
}
