export interface ParsedSymbol {
  name: string;
  kind: "group" | "dependency";
  range: { startLine: number; startCol: number; endLine: number; endCol: number };
  children?: ParsedSymbol[];
}

/** Structural regexes for detecting dependency array contexts. */
const simpleGroupStart = /^(\s*)([\w][\w.-]*)\s*=\s*\[/;
const advancedGroupStart = /^(\s*)([\w][\w.-]*)\s*\{/;
const dependenciesArrayStart = /^\s*dependencies\s*=\s*\[/;

type ParserState = "outside" | "simple_array" | "advanced_block" | "dependencies_array";

/**
 * Parses lines from a `dependencies.conf` file and returns document symbols
 * representing groups and their dependency children.
 *
 * Uses a line-based state machine (same approach as `diagnostics.ts`).
 */
export function parseDocumentSymbols(lines: string[]): ParsedSymbol[] {
  const symbols: ParsedSymbol[] = [];
  let state: ParserState = "outside";
  let inBlockComment = false;
  let currentGroup: ParsedSymbol | undefined;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // Strip block/line comments (same logic as diagnostics.ts)
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
      const simpleMatch = simpleGroupStart.exec(effectiveLine);
      if (simpleMatch) {
        const name = simpleMatch[2];
        const startCol = simpleMatch[1].length;
        currentGroup = {
          name,
          kind: "group",
          range: { startLine: i, startCol, endLine: i, endCol: startCol + name.length },
          children: [],
        };
        if (effectiveLine.includes("]")) {
          extractDependencies(line, i, currentGroup);
          symbols.push(currentGroup);
          currentGroup = undefined;
          state = "outside";
        } else {
          state = "simple_array";
        }
        continue;
      }

      const advancedMatch = advancedGroupStart.exec(effectiveLine);
      if (advancedMatch) {
        const name = advancedMatch[2];
        const startCol = advancedMatch[1].length;
        currentGroup = {
          name,
          kind: "group",
          range: { startLine: i, startCol, endLine: i, endCol: startCol + name.length },
          children: [],
        };
        state = "advanced_block";
        continue;
      }
    } else if (state === "simple_array") {
      extractDependencies(line, i, currentGroup!);
      if (effectiveLine.includes("]")) {
        currentGroup!.range.endLine = i;
        currentGroup!.range.endCol = line.length;
        symbols.push(currentGroup!);
        currentGroup = undefined;
        state = "outside";
      }
    } else if (state === "advanced_block") {
      if (dependenciesArrayStart.test(effectiveLine)) {
        if (effectiveLine.includes("]")) {
          extractDependencies(line, i, currentGroup!);
        } else {
          state = "dependencies_array";
        }
      } else if (effectiveLine.includes("}")) {
        currentGroup!.range.endLine = i;
        currentGroup!.range.endCol = line.length;
        symbols.push(currentGroup!);
        currentGroup = undefined;
        state = "outside";
      }
    } else if (state === "dependencies_array") {
      extractDependencies(line, i, currentGroup!);
      if (effectiveLine.includes("]")) {
        state = "advanced_block";
      }
    }
  }

  return symbols;
}

/** Extracts quoted dependency strings from a line and adds them as children. */
function extractDependencies(line: string, lineIndex: number, group: ParsedSymbol): void {
  const stringPattern = /"([^"]*)"/g;
  let match;
  while ((match = stringPattern.exec(line)) !== null) {
    const content = match[1];
    if (content.length === 0) continue;
    const startCol = match.index + 1;
    group.children!.push({
      name: content,
      kind: "dependency",
      range: { startLine: lineIndex, startCol, endLine: lineIndex, endCol: startCol + content.length },
    });
  }
}
