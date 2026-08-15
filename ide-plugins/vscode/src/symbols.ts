import { walkDocument, objectDepFieldPattern } from "./parser";

export interface ParsedSymbol {
  name: string;
  kind: "group" | "dependency";
  range: { startLine: number; startCol: number; endLine: number; endCol: number };
  children?: ParsedSymbol[];
}

/**
 * Parses lines from a `dependencies.conf` file and returns document symbols
 * representing groups and their dependency children.
 *
 * Supports both plain string entries and object entries with `dependency` and `note` fields.
 */
export function parseDocumentSymbols(lines: string[]): ParsedSymbol[] {
  const symbols: ParsedSymbol[] = [];
  let currentGroup: ParsedSymbol | undefined;

  for (const event of walkDocument(lines)) {
    switch (event.type) {
      case "group-start": {
        currentGroup = {
          name: event.name,
          kind: "group",
          range: { startLine: event.lineIndex, startCol: event.startCol, endLine: event.lineIndex, endCol: event.nameEndCol },
          children: [],
        };
        break;
      }
      case "group-end": {
        if (currentGroup) {
          currentGroup.range.endLine = event.lineIndex;
          currentGroup.range.endCol = event.rawLine.length;
          symbols.push(currentGroup);
          currentGroup = undefined;
        }
        break;
      }
      case "dependency-string": {
        if (currentGroup) {
          currentGroup.children!.push({
            name: event.content,
            kind: "dependency",
            range: { startLine: event.lineIndex, startCol: event.startCol, endLine: event.lineIndex, endCol: event.endCol },
          });
        }
        break;
      }
      case "single-line-object": {
        if (currentGroup && event.dependency && event.dependency.length > 0 && event.dependencyStartCol !== undefined) {
          currentGroup.children!.push({
            name: event.dependency,
            kind: "dependency",
            range: { startLine: event.lineIndex, startCol: event.dependencyStartCol, endLine: event.lineIndex, endCol: event.dependencyStartCol + event.dependency.length },
          });
        }
        break;
      }
      case "multi-line-object-end": {
        if (currentGroup && event.dependencyValue && event.dependencyValue.length > 0 && event.dependencyStartCol !== undefined && event.dependencyLineIndex !== undefined) {
          currentGroup.children!.push({
            name: event.dependencyValue,
            kind: "dependency",
            range: { startLine: event.dependencyLineIndex, startCol: event.dependencyStartCol, endLine: event.dependencyLineIndex, endCol: event.dependencyStartCol + event.dependencyValue.length },
          });
        }
        break;
      }
    }
  }

  return symbols;
}
