import * as path from "node:path";

/** One flattened `<dependencyManagement>` entry: a concrete (already Scala-suffixed) artifact a BOM pins. */
export interface BomPin {
  organization: string;
  name: string;
  version: string;
}

/** A BOM's coordinate and its flattened pins, stored once and referenced by key from every project that sees it. */
export interface Bom {
  organization: string;
  name: string;
  version: string;
  entries: BomPin[];
}

/** A dependency whose `{{variable}}` version resolved, matched by organization + name + cross. */
export interface VariableResolution {
  organization: string;
  name: string;
  cross: boolean;
  variable: string;
  version: string;
}

/** One group's resolutions: the Scala binary versions it builds for, its visible BOMs (keys, precedence order) and its resolved variables. */
export interface ProjectResolutions {
  scalaBinaryVersions: string[];
  boms: string[];
  variables: VariableResolution[];
}

/** The parsed `.sbt-resolutions` dump written by the sbt plugin on load. */
export interface ResolutionsDump {
  version: number;
  sourceHash?: string;
  boms: Record<string, Bom>;
  projects: Record<string, ProjectResolutions>;
}

/** A resolved `*` version together with the BOM that pins it. */
export interface WildcardResolution {
  version: string;
  bom: { organization: string; name: string; version: string };
}

/** A resolved `{{variable}}` version together with the variable's name. */
export interface VariableLookupResult {
  version: string;
  variable: string;
}

/**
 * The read API consumed by the feature modules (decorations, hover, quickfixes), abstracting over the
 * {@link ResolutionsIndex} so tests can pass a fake. `stale` is `true` when the dump is older than the buffer it's
 * resolving against.
 */
export interface ResolutionLookup {
  resolveWildcard(group: string, org: string, name: string, isCross: boolean): WildcardResolution | undefined;
  resolveVariable(group: string, org: string, name: string, isCross: boolean): VariableLookupResult | undefined;
  pinFor(group: string, org: string, name: string, isCross: boolean): WildcardResolution | undefined;
  stale: boolean;
}

/** The reserved group name whose resolutions live in the meta-build dump. */
const SBT_BUILD_GROUP = "sbt-build";

/**
 * The two dump locations for a `dependencies.conf` at `<root>/project/dependencies.conf`: the main build's dump under
 * `<root>/target` and the meta-build's under `<root>/project/target`.
 */
export function dumpPathsFor(confFsPath: string): { main: string; meta: string } {
  const projectDir = path.dirname(confFsPath);
  const root = path.dirname(projectDir);
  const rel = path.join("target", "sbt-dependencies", ".sbt-resolutions");

  return { main: path.join(root, rel), meta: path.join(projectDir, rel) };
}

/** Parses a dump, returning `undefined` on malformed JSON or an unsupported `version` so callers silently disable. */
export function parseResolutionsDump(json: string): ResolutionsDump | undefined {
  try {
    const parsed = JSON.parse(json);
    if (parsed?.version !== 1 || typeof parsed.boms !== "object" || typeof parsed.projects !== "object") {
      return undefined;
    }
    return parsed as ResolutionsDump;
  } catch {
    return undefined;
  }
}

/**
 * Answers `*` and `{{variable}}` resolutions from the main and meta dumps, routing `sbt-build` to the meta dump and
 * every other group to the main one. Per-group `*` lookup maps are built on first use (a real BOM has 1000+ pins) with
 * first-BOM-wins precedence, matching the plugin.
 */
export class ResolutionsIndex {
  private readonly wildcardCache = new Map<string, Map<string, WildcardResolution>>();

  constructor(
    private readonly main: ResolutionsDump | undefined,
    private readonly meta: ResolutionsDump | undefined
  ) {}

  /** Whether either dump was loaded. */
  get hasData(): boolean {
    return this.main !== undefined || this.meta !== undefined;
  }

  private dumpFor(group: string): ResolutionsDump | undefined {
    return group === SBT_BUILD_GROUP ? this.meta : this.main;
  }

  private concreteName(name: string, isCross: boolean, sbv: string): string {
    return isCross ? `${name}_${sbv}` : name;
  }

  /** The first-wins `org:concreteArtifact` → resolution map for a group, built once and memoized. */
  private pinsFor(group: string): Map<string, WildcardResolution> {
    const cached = this.wildcardCache.get(group);
    if (cached) return cached;

    const map = new Map<string, WildcardResolution>();
    const dump = this.dumpFor(group);
    const project = dump?.projects[group];

    if (dump && project) {
      for (const key of project.boms) {
        const bom = dump.boms[key];
        if (!bom) continue;
        for (const pin of bom.entries) {
          const mapKey = `${pin.organization}:${pin.name}`;
          if (!map.has(mapKey)) {
            map.set(mapKey, {
              version: pin.version,
              bom: { organization: bom.organization, name: bom.name, version: bom.version },
            });
          }
        }
      }
    }

    this.wildcardCache.set(group, map);
    return map;
  }

  resolveWildcard(group: string, org: string, name: string, isCross: boolean): WildcardResolution | undefined {
    const dump = this.dumpFor(group);
    const project = dump?.projects[group];
    if (!project) return undefined;

    const sbv = project.scalaBinaryVersions[0];
    return this.pinsFor(group).get(`${org}:${this.concreteName(name, isCross, sbv)}`);
  }

  resolveVariable(group: string, org: string, name: string, isCross: boolean): VariableLookupResult | undefined {
    const dump = this.dumpFor(group);
    const project = dump?.projects[group];
    if (!project) return undefined;

    const match = project.variables.find(
      (v) => v.organization === org && v.name === name && v.cross === isCross
    );
    return match ? { version: match.version, variable: match.variable } : undefined;
  }

  pinFor(group: string, org: string, name: string, isCross: boolean): WildcardResolution | undefined {
    return this.resolveWildcard(group, org, name, isCross);
  }

  /** Wraps this index as a {@link ResolutionLookup} carrying the given staleness. */
  asLookup(stale: boolean): ResolutionLookup {
    return {
      resolveWildcard: this.resolveWildcard.bind(this),
      resolveVariable: this.resolveVariable.bind(this),
      pinFor: this.pinFor.bind(this),
      stale,
    };
  }
}
