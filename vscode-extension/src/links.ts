import { parseDependency, buildMvnRepositoryUrl, type DependencyMatch } from "./hover";

export interface ParsedLink {
  range: { startLine: number; startCol: number; endLine: number; endCol: number };
  url: string;
}

/**
 * Scans lines from a `dependencies.conf` file and returns one link per
 * dependency found.
 *
 * When a `resolveUrl` callback is provided, it is called first; if it
 * returns a URL that value is used.  Otherwise the link falls back to
 * mvnrepository.com.
 *
 * Availability filtering is NOT performed here — that belongs in the
 * VS Code provider layer.
 */
export function parseDocumentLinks(
  lines: string[],
  resolveUrl?: (dep: DependencyMatch) => string | undefined
): ParsedLink[] {
  const links: ParsedLink[] = [];

  for (let i = 0; i < lines.length; i++) {
    const dep = parseDependency(lines[i]);
    if (!dep) continue;

    links.push({
      range: {
        startLine: i,
        startCol: dep.matchStart,
        endLine: i,
        endCol: dep.matchEnd,
      },
      url: resolveUrl?.(dep) ?? buildMvnRepositoryUrl(dep),
    });
  }

  return links;
}
