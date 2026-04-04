// ── Shared regexes ──────────────────────────────────────────────────

export const simpleGroupStartPattern = /^(\s*)([\w][\w.-]*)\s*=\s*\[/;
export const advancedGroupStartPattern = /^(\s*)([\w][\w.-]*)\s*\{/;
export const dependenciesArrayStartPattern = /^\s*dependencies\s*=\s*\[/;
export const objectDepFieldPattern = /dependency\s*=\s*"([^"]*)"/;
export const objectNoteFieldPattern = /note\s*=\s*"([^"]*)"/;
export const objectIntransitiveFieldPattern = /intransitive\s*=\s*true/;
export const objectScalaFilterFieldPattern = /scala-filter\s*=\s*"([^"]*)"/;
export const singleLineObjectPattern = /\{(?:[^}"{]*(?:"[^"]*")?)*\}/g;

// ── Event types ─────────────────────────────────────────────────────

export type DocumentEvent =
  | GroupStartEvent
  | GroupEndEvent
  | DependencyStringEvent
  | SingleLineObjectEvent
  | MultiLineObjectStartEvent
  | MultiLineObjectFieldEvent
  | MultiLineObjectEndEvent
  | SettingLineEvent;

export interface GroupStartEvent {
  type: "group-start";
  name: string;
  lineIndex: number;
  startCol: number;
  nameEndCol: number;
  groupKind: "simple" | "advanced";
  /** True if the array opens and closes on the same line. */
  singleLine: boolean;
  rawLine: string;
  effectiveLine: string;
}

export interface GroupEndEvent {
  type: "group-end";
  lineIndex: number;
  rawLine: string;
  groupKind: "simple" | "advanced";
}

export interface DependencyStringEvent {
  type: "dependency-string";
  /** The quoted string content (without quotes). */
  content: string;
  lineIndex: number;
  /** Column of the first character inside the quotes. */
  startCol: number;
  endCol: number;
  rawLine: string;
  arrayKind: "simple" | "dependencies";
}

export interface SingleLineObjectEvent {
  type: "single-line-object";
  lineIndex: number;
  /** The full `{...}` match. */
  objectText: string;
  objectStartCol: number;
  dependency: string | undefined;
  dependencyStartCol: number | undefined;
  note: string | undefined;
  intransitive: boolean;
  scalaFilter: string | undefined;
  rawLine: string;
  arrayKind: "simple" | "dependencies";
}

export interface MultiLineObjectStartEvent {
  type: "multi-line-object-start";
  lineIndex: number;
  rawLine: string;
  effectiveLine: string;
  arrayKind: "simple" | "dependencies";
}

export interface MultiLineObjectFieldEvent {
  type: "multi-line-object-field";
  lineIndex: number;
  rawLine: string;
  effectiveLine: string;
  field: "dependency" | "note" | "intransitive" | "scala-filter" | null;
  fieldValue: string | undefined;
  fieldValueStartCol: number | undefined;
}

export interface MultiLineObjectEndEvent {
  type: "multi-line-object-end";
  lineIndex: number;
  rawLine: string;
  /** All raw lines from object-start through object-end (inclusive). */
  objectLines: string[];
  hasDependency: boolean;
  dependencyValue: string | undefined;
  dependencyStartCol: number | undefined;
  dependencyLineIndex: number | undefined;
  hasNote: boolean;
  noteValue: string | undefined;
  hasIntransitive: boolean;
  hasScalaFilter: boolean;
  scalaFilterValue: string | undefined;
  objectStartLine: number;
  arrayKind: "simple" | "dependencies";
}

export interface SettingLineEvent {
  type: "setting-line";
  lineIndex: number;
  rawLine: string;
  effectiveLine: string;
}

// ── Comment stripping ───────────────────────────────────────────────

export function stripComments(
  line: string,
  inBlockComment: boolean
): { effectiveLine: string; inBlockComment: boolean } {
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

  return { effectiveLine, inBlockComment };
}

// ── Helpers ─────────────────────────────────────────────────────────

/** Strips quoted strings so brace checks ignore content inside `"..."`. */
function stripQuotedStrings(line: string): string {
  return line.replace(/"[^"]*"/g, "");
}

function detectField(
  effectiveLine: string,
  rawLine: string
): { field: "dependency" | "note" | "intransitive" | "scala-filter" | null; fieldValue: string | undefined; fieldValueStartCol: number | undefined } {
  const depMatch = objectDepFieldPattern.exec(rawLine);
  if (depMatch) {
    return {
      field: "dependency",
      fieldValue: depMatch[1],
      fieldValueStartCol: depMatch.index + depMatch[0].indexOf('"') + 1,
    };
  }
  const noteMatch = objectNoteFieldPattern.exec(effectiveLine);
  if (noteMatch) {
    return { field: "note", fieldValue: noteMatch[1], fieldValueStartCol: undefined };
  }
  if (objectIntransitiveFieldPattern.test(effectiveLine)) {
    return { field: "intransitive", fieldValue: undefined, fieldValueStartCol: undefined };
  }
  const sfMatch = objectScalaFilterFieldPattern.exec(effectiveLine);
  if (sfMatch) {
    return { field: "scala-filter", fieldValue: sfMatch[1], fieldValueStartCol: undefined };
  }
  return { field: null, fieldValue: undefined, fieldValueStartCol: undefined };
}

// ── The generator ───────────────────────────────────────────────────

type ParserState = "outside" | "simple_array" | "advanced_block" | "dependencies_array" | "dependency_object";

export function* walkDocument(lines: string[]): Generator<DocumentEvent> {
  let state: ParserState = "outside";
  let preObjectState: "simple_array" | "dependencies_array" = "simple_array";
  let inBlockComment = false;

  // Multi-line object accumulator
  let objectLines: string[] = [];
  let objectStartLine = 0;
  let objectHasDep = false;
  let objectDepValue: string | undefined;
  let objectDepStartCol: number | undefined;
  let objectDepLineIndex: number | undefined;
  let objectHasNote = false;
  let objectNoteValue: string | undefined;
  let objectHasIntransitive = false;
  let objectHasScalaFilter = false;
  let objectScalaFilterValue: string | undefined;

  function resetObjectTracking(lineIndex: number) {
    objectLines = [];
    objectStartLine = lineIndex;
    objectHasDep = false;
    objectDepValue = undefined;
    objectDepStartCol = undefined;
    objectDepLineIndex = undefined;
    objectHasNote = false;
    objectNoteValue = undefined;
    objectHasIntransitive = false;
    objectHasScalaFilter = false;
    objectScalaFilterValue = undefined;
  }

  function trackObjectField(effectiveLine: string, rawLine: string, lineIndex: number) {
    objectLines.push(rawLine);
    const depMatch = objectDepFieldPattern.exec(rawLine);
    if (depMatch) {
      objectHasDep = true;
      objectDepValue = depMatch[1];
      objectDepStartCol = depMatch.index + depMatch[0].indexOf('"') + 1;
      objectDepLineIndex = lineIndex;
    }
    if (objectNoteFieldPattern.test(effectiveLine)) {
      objectHasNote = true;
      const noteMatch = objectNoteFieldPattern.exec(effectiveLine);
      if (noteMatch) objectNoteValue = noteMatch[1];
    }
    if (objectIntransitiveFieldPattern.test(effectiveLine)) {
      objectHasIntransitive = true;
    }
    const sfMatch = objectScalaFilterFieldPattern.exec(effectiveLine);
    if (sfMatch) {
      objectHasScalaFilter = true;
      objectScalaFilterValue = sfMatch[1];
    }
  }

  function arrayKind(): "simple" | "dependencies" {
    return preObjectState === "simple_array" ? "simple" : "dependencies";
  }

  function currentArrayKind(): "simple" | "dependencies" {
    if (state === "simple_array") return "simple";
    if (state === "dependencies_array") return "dependencies";
    return preObjectState === "simple_array" ? "simple" : "dependencies";
  }

  for (let i = 0; i < lines.length; i++) {
    const rawLine = lines[i];
    const result = stripComments(rawLine, inBlockComment);
    const effectiveLine = result.effectiveLine;
    inBlockComment = result.inBlockComment;

    // ── dependency_object state ──
    if (state === "dependency_object") {
      trackObjectField(effectiveLine, rawLine, i);

      const { field, fieldValue, fieldValueStartCol } = detectField(effectiveLine, rawLine);
      yield {
        type: "multi-line-object-field",
        lineIndex: i,
        rawLine,
        effectiveLine,
        field,
        fieldValue,
        fieldValueStartCol,
      };

      if (stripQuotedStrings(effectiveLine).includes("}")) {
        yield {
          type: "multi-line-object-end",
          lineIndex: i,
          rawLine,
          objectLines: [...objectLines],
          hasDependency: objectHasDep,
          dependencyValue: objectDepValue,
          dependencyStartCol: objectDepStartCol,
          dependencyLineIndex: objectDepLineIndex,
          hasNote: objectHasNote,
          noteValue: objectNoteValue,
          hasIntransitive: objectHasIntransitive,
          hasScalaFilter: objectHasScalaFilter,
          scalaFilterValue: objectScalaFilterValue,
          objectStartLine,
          arrayKind: arrayKind(),
        };
        state = preObjectState;
      }
      continue;
    }

    // ── outside state ──
    if (state === "outside") {
      const simpleMatch = simpleGroupStartPattern.exec(effectiveLine);
      if (simpleMatch) {
        const name = simpleMatch[2];
        const startCol = simpleMatch[1].length;
        const singleLine = effectiveLine.includes("]");
        yield {
          type: "group-start",
          name,
          lineIndex: i,
          startCol,
          nameEndCol: startCol + name.length,
          groupKind: "simple",
          singleLine,
          rawLine,
          effectiveLine,
        };
        if (singleLine) {
          // Emit deps on this single line
          yield* emitDependenciesOnLine(rawLine, effectiveLine, i, "simple");
          yield { type: "group-end", lineIndex: i, rawLine, groupKind: "simple" };
          state = "outside";
        } else {
          state = "simple_array";
        }
        continue;
      }

      const advancedMatch = advancedGroupStartPattern.exec(effectiveLine);
      if (advancedMatch) {
        const name = advancedMatch[2];
        const startCol = advancedMatch[1].length;
        yield {
          type: "group-start",
          name,
          lineIndex: i,
          startCol,
          nameEndCol: startCol + name.length,
          groupKind: "advanced",
          singleLine: false,
          rawLine,
          effectiveLine,
        };
        state = "advanced_block";
        continue;
      }
      continue;
    }

    // ── advanced_block state ──
    if (state === "advanced_block") {
      if (dependenciesArrayStartPattern.test(effectiveLine)) {
        if (effectiveLine.includes("]")) {
          yield* emitDependenciesOnLine(rawLine, effectiveLine, i, "dependencies");
        } else {
          state = "dependencies_array";
        }
        continue;
      }

      if (effectiveLine.includes("}")) {
        yield { type: "group-end", lineIndex: i, rawLine, groupKind: "advanced" };
        state = "outside";
        continue;
      }

      yield { type: "setting-line", lineIndex: i, rawLine, effectiveLine };
      continue;
    }

    // ── simple_array / dependencies_array states ──
    if (state === "simple_array" || state === "dependencies_array") {
      const ak = currentArrayKind();
      const closingArray = state === "simple_array" ? "]" : "]";
      const isClosing = effectiveLine.includes("]");

      // Check for multi-line object start (strip quoted strings to avoid matching `}}` in `{{var}}`)
      const unquoted = stripQuotedStrings(effectiveLine);
      if (unquoted.includes("{") && !unquoted.includes("}")) {
        preObjectState = state as "simple_array" | "dependencies_array";
        resetObjectTracking(i);
        trackObjectField(effectiveLine, rawLine, i);

        const { field, fieldValue, fieldValueStartCol } = detectField(effectiveLine, rawLine);

        yield { type: "multi-line-object-start", lineIndex: i, rawLine, effectiveLine, arrayKind: ak };
        yield {
          type: "multi-line-object-field",
          lineIndex: i,
          rawLine,
          effectiveLine,
          field,
          fieldValue,
          fieldValueStartCol,
        };
        state = "dependency_object";
        continue;
      }

      // Emit single-line objects and plain strings on this line
      yield* emitDependenciesOnLine(rawLine, effectiveLine, i, ak);

      if (isClosing) {
        if (state === "simple_array") {
          yield { type: "group-end", lineIndex: i, rawLine, groupKind: "simple" };
          state = "outside";
        } else {
          state = "advanced_block";
        }
      }
      continue;
    }
  }
}

/**
 * Emits SingleLineObjectEvent and DependencyStringEvent for objects and plain
 * strings found on a single line. Objects are detected first, then remaining
 * quoted strings outside object ranges are emitted as dependency strings.
 */
function* emitDependenciesOnLine(
  rawLine: string,
  effectiveLine: string,
  lineIndex: number,
  ak: "simple" | "dependencies"
): Generator<SingleLineObjectEvent | DependencyStringEvent> {
  singleLineObjectPattern.lastIndex = 0;
  let objMatch;
  const objectRanges: [number, number][] = [];

  while ((objMatch = singleLineObjectPattern.exec(effectiveLine)) !== null) {
    const objectText = objMatch[0];
    const objectStartCol = objMatch.index;

    // Skip {…} matches inside quoted strings (e.g. "org::art:{{var}}")
    const quotesBefore = (effectiveLine.substring(0, objectStartCol).match(/"/g) || []).length;
    if (quotesBefore % 2 === 1) continue;

    objectRanges.push([objectStartCol, objectStartCol + objectText.length]);

    const depMatch = objectDepFieldPattern.exec(objectText);
    const noteMatch = objectNoteFieldPattern.exec(objectText);
    const sfMatch = objectScalaFilterFieldPattern.exec(objectText);

    let dependencyStartCol: number | undefined;
    if (depMatch) {
      dependencyStartCol = objectStartCol + depMatch.index + depMatch[0].indexOf('"') + 1;
    }

    yield {
      type: "single-line-object",
      lineIndex,
      objectText,
      objectStartCol,
      dependency: depMatch?.[1],
      dependencyStartCol,
      note: noteMatch?.[1],
      intransitive: objectIntransitiveFieldPattern.test(objectText),
      scalaFilter: sfMatch?.[1],
      rawLine,
      arrayKind: ak,
    };
  }

  // Emit plain string entries, skipping regions covered by objects
  const stringPattern = /"([^"]*)"/g;
  let strMatch;
  while ((strMatch = stringPattern.exec(effectiveLine)) !== null) {
    const inObject = objectRanges.some(([start, end]) => strMatch!.index >= start && strMatch!.index < end);
    if (inObject) continue;

    const content = strMatch[1];
    if (content.length === 0) continue;

    yield {
      type: "dependency-string",
      content,
      lineIndex,
      startCol: strMatch.index + 1,
      endCol: strMatch.index + 1 + content.length,
      rawLine,
      arrayKind: ak,
    };
  }
}
