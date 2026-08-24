export interface DependencyMatch {
  org: string;
  separator: string;
  artifact: string;
  version?: string;
  config?: string;
  matchStart: number;
  matchEnd: number;
}

/**
 * Matches dependency declarations in the form `org::artifact:version`.
 *
 * - Group 1: organization (e.g. `org.typelevel`)
 * - Group 2: separator (`:` for Java, `::` for Scala)
 * - Group 3: artifact name (e.g. `cats-core`)
 * - Group 4: version (e.g. `^2.10.0`, `{{catsVersion}}` or `*`), if present
 * - Group 5: configuration (e.g. `sbt-plugin`), if present
 */
export const dependencyPattern =
  /([^\s:"]+)(::?)([^\s:"]+)(?::(\{\{\w+\}\}|\*|[=^~]?\d[^\s:"]*)(?::([^\s:"]+))?)?/g;

/**
 * Runs the dependency regex against a line and returns parsed fields,
 * or `undefined` if no match.
 */
export function parseDependency(line: string): DependencyMatch | undefined {
  dependencyPattern.lastIndex = 0;
  const match = dependencyPattern.exec(line);

  if (!match || match.index === undefined) return undefined;

  return {
    org: match[1],
    separator: match[2],
    artifact: match[3],
    version: match[4],
    config: match[5],
    matchStart: match.index,
    matchEnd: match.index + match[0].length,
  };
}

/**
 * Builds a mvnrepository.com URL for the given dependency.
 */
export function buildMvnRepositoryUrl(dep: DependencyMatch): string {
  const isSbtPlugin = dep.config === "sbt-plugin";
  const artifactForUrl = isSbtPlugin ? `${dep.artifact}_2.12_1.0` : dep.artifact;
  return `https://mvnrepository.com/artifact/${dep.org}/${artifactForUrl}`;
}

/** A resolved version shown on hover, together with where it came from. */
export interface HoverResolution {
  version: string;
  stale: boolean;
  source:
    | { kind: "bom"; organization: string; name: string; bomVersion: string }
    | { kind: "variable"; variable: string };
}

/**
 * Builds the full markdown hover string for a dependency.
 *
 * Includes organization, artifact, version marker explanation,
 * configuration, the resolved version and its provenance (for `*`/`{{variable}}` deps),
 * and optionally a link to mvnrepository.com.
 */
export function buildHoverMarkdown(dep: DependencyMatch, available: boolean, resolution?: HoverResolution): string {
  let md = `**${dep.org}** \`${dep.separator}\` **${dep.artifact}**\n\n`;

  if (dep.version) {
    let explanation: string;
    if (dep.version === "*") {
      explanation = "managed by BOM";
    } else if (dep.version.startsWith("{{")) {
      explanation = "resolved from variable";
    } else if (dep.version.startsWith("=")) {
      explanation = "pinned";
    } else if (dep.version.startsWith("^")) {
      explanation = "update within major";
    } else if (dep.version.startsWith("~")) {
      explanation = "update within minor";
    } else {
      explanation = "update to latest";
    }
    md += `Version: \`${dep.version}\` *(${explanation})*`;
  } else {
    md += `Version: *resolved to latest*`;
  }

  if (dep.config) {
    md += `\\\nConfiguration: \`${dep.config}\``;
  }

  if (resolution) {
    const provenance =
      resolution.source.kind === "bom"
        ? `pinned by \`${resolution.source.organization}:${resolution.source.name}:${resolution.source.bomVersion}\``
        : `from variable \`${resolution.source.variable}\``;
    const staleness = resolution.stale ? " *(stale — reload sbt)*" : "";
    md += `\n\nResolved: \`${resolution.version}\` — ${provenance}${staleness}`;
  }

  if (available) {
    md += `\n\n[Open on mvnrepository](${buildMvnRepositoryUrl(dep)})`;
  }

  return md;
}

/**
 * Rewrites every dependency-string version in `text` to `*`, so two documents that differ only in version tokens
 * normalize to the same string. BOM pins are keyed by group/org/artifact, so a version-only edit (a rewrite to `*`, a
 * manual bump) leaves them valid — callers use this to keep pin-based features alive on an otherwise stale dump.
 */
export function normalizeVersions(text: string): string {
  return text
    .split("\n")
    .map((line) => {
      const dep = parseDependency(line);
      if (!dep?.version) return line;
      const versionStart = dep.matchStart + dep.org.length + dep.separator.length + dep.artifact.length + 1;
      return line.slice(0, versionStart) + "*" + line.slice(versionStart + dep.version.length);
    })
    .join("\n");
}
