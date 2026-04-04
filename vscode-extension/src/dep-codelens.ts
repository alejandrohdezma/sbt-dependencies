import { parseDependency } from "./hover";
import { walkDocument, objectDepFieldPattern } from "./parser";

export interface DepCodeLensData {
  line: number;
  org: string;
  artifact: string;
  version: string;
  reason: "pinned" | "intransitive";
}

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

  for (const event of walkDocument(lines)) {
    switch (event.type) {
      case "dependency-string": {
        const dep = parseDependency(event.rawLine);
        if (dep && dep.version && /^[=^~]/.test(dep.version)) {
          results.push({
            line: event.lineIndex,
            org: dep.org,
            artifact: dep.artifact,
            version: dep.version,
            reason: "pinned",
          });
        }
        break;
      }
      case "single-line-object": {
        if (event.intransitive && !event.note) {
          const depMatch = event.dependency ? objectDepFieldPattern.exec(event.objectText) : undefined;
          if (depMatch) {
            const dep = parseDependency(depMatch[1]);
            if (dep) {
              results.push({
                line: event.lineIndex,
                org: dep.org,
                artifact: dep.artifact,
                version: dep.version ?? "",
                reason: "intransitive",
              });
            }
          }
        }
        break;
      }
      case "multi-line-object-end": {
        if (event.hasIntransitive && !event.hasNote && event.dependencyValue) {
          const dep = parseDependency(event.dependencyValue);
          if (dep) {
            results.push({
              line: event.objectStartLine,
              org: dep.org,
              artifact: dep.artifact,
              version: dep.version ?? "",
              reason: "intransitive",
            });
          }
        }
        break;
      }
    }
  }

  return results;
}
