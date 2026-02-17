import { parseDependency, buildMvnRepositoryUrl } from "./hover";

export interface ParsedLink {
  range: { startLine: number; startCol: number; endLine: number; endCol: number };
  url: string;
}

/**
 * Scans lines from a `dependencies.conf` file and returns one link per
 * dependency found, pointing to its mvnrepository.com page.
 *
 * Availability filtering is NOT performed here — that belongs in the
 * VS Code provider layer.
 */
export function parseDocumentLinks(lines: string[]): ParsedLink[] {
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
      url: buildMvnRepositoryUrl(dep),
    });
  }

  return links;
}
