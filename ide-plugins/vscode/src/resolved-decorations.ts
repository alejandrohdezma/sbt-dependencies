import { walkDocument } from "./parser";
import { parseDependency } from "./hover";
import { ResolutionLookup } from "./resolutions";

export interface ResolvedDecorationData {
  line: number;
  /** Column right after the dependency string's closing quote, where the resolved version is shown. */
  afterCol: number;
  /** The ghost text to render, e.g. ` = 2.17.0` (with a ` (stale)` suffix when the dump is out of date). */
  text: string;
}

/**
 * Scans lines for plain dependency strings whose version is `*` or `{{variable}}` and, when the resolutions dump
 * resolves them, produces ghost-text decorations showing the concrete version.
 *
 * Only plain string entries are decorated: object-form entries (`{ dependency = "...", note = "..." }`) render their
 * note inline via note-decorations at the same column, and provenance is available on hover instead.
 *
 * ponytail: plain-string-only — object-form `*`/`{{var}}` deps are rare and covered by hover.
 */
export function parseResolvedDecorations(lines: string[], lookup: ResolutionLookup): ResolvedDecorationData[] {
  const results: ResolvedDecorationData[] = [];
  let group: string | undefined;

  for (const event of walkDocument(lines)) {
    if (event.type === "group-start") {
      group = event.name;
      continue;
    }
    if (event.type === "group-end") {
      group = undefined;
      continue;
    }
    if (event.type !== "dependency-string" || group === undefined) continue;

    const dep = parseDependency(event.content);
    if (!dep?.version) continue;

    const isCross = dep.separator === "::";

    let version: string | undefined;
    if (dep.version === "*") {
      version = lookup.resolveWildcard(group, dep.org, dep.artifact, isCross)?.version;
    } else if (dep.version.startsWith("{{")) {
      version = lookup.resolveVariable(group, dep.org, dep.artifact, isCross)?.version;
    }
    if (version === undefined) continue;

    results.push({
      line: event.lineIndex,
      afterCol: event.startCol + event.content.length + 1,
      text: ` = ${version}${lookup.stale ? " (stale)" : ""}`,
    });
  }

  return results;
}
