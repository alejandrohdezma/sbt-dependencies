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
 * - Group 4: version (e.g. `^2.10.0`), if present
 * - Group 5: configuration (e.g. `sbt-plugin`), if present
 */
export const dependencyPattern =
  /([^\s:"]+)(::?)([^\s:"]+)(?::(\{\{\w+\}\}|[=^~]?\d[^\s:"]*)(?::([^\s:"]+))?)?/g;

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

/**
 * Builds the full markdown hover string for a dependency.
 *
 * Includes organization, artifact, version marker explanation,
 * configuration, and optionally a link to mvnrepository.com.
 */
export function buildHoverMarkdown(dep: DependencyMatch, available: boolean): string {
  let md = `**${dep.org}** \`${dep.separator}\` **${dep.artifact}**\n\n`;

  if (dep.version) {
    let explanation: string;
    if (dep.version.startsWith("{{")) {
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

  if (available) {
    md += `\n\n[Open on mvnrepository](${buildMvnRepositoryUrl(dep)})`;
  }

  return md;
}
