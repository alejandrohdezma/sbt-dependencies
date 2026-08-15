import { parseDependency } from "./hover";
import { walkDocument, objectDepFieldPattern } from "./parser";
import { ResolutionLookup } from "./resolutions";

export interface DepCodeLensData {
  line: number;
  org: string;
  artifact: string;
  version: string;
  reason: "pinned" | "intransitive";
}

export interface BomManagedLensData {
  line: number;
  /** The hardcoded version the BOM manages. */
  version: string;
  /** The name of the BOM that pins the artifact. */
  bomName: string;
}

/**
 * Scans plain dependency strings for a hardcoded version that a visible BOM pins, producing CodeLens data suggesting
 * the version could be replaced with `*`. Skips `*`/`{{variable}}` versions and `bom`/`sbt-plugin` configurations
 * (which cannot take a BOM-managed version). Callers should skip this when the dump is stale.
 *
 * ponytail: plain-string-only — object-form entries carry notes/cross-version and are left alone.
 */
export function parseBomManagedVersions(lines: string[], lookup: ResolutionLookup): BomManagedLensData[] {
  const results: BomManagedLensData[] = [];
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
    if (!dep?.version || dep.version === "*" || dep.version.startsWith("{{")) continue;
    if (dep.config === "bom" || dep.config === "sbt-plugin") continue;

    const pin = lookup.pinFor(group, dep.org, dep.artifact, dep.separator === "::");
    if (pin) results.push({ line: event.lineIndex, version: dep.version, bomName: pin.bom.name });
  }

  return results;
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
