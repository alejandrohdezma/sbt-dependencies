import * as path from "node:path";
import * as os from "node:os";

import type { DependencyMatch } from "./hover";

type ReadDir = (dir: string) => string[];
type ReadFile = (file: string) => string | undefined;

/**
 * Returns the Coursier cache directory.
 *
 * Checks the `COURSIER_CACHE` env var first, then falls back to
 * platform-specific defaults.
 */
export function getCoursierCachePath(
  env: Record<string, string | undefined> = process.env,
  platform: string = process.platform
): string {
  if (env.COURSIER_CACHE) return env.COURSIER_CACHE;

  const home = os.homedir();

  if (platform === "darwin") {
    return path.join(home, "Library", "Caches", "Coursier", "v1");
  }

  if (platform === "win32") {
    const localAppData = env.LOCALAPPDATA ?? path.join(home, "AppData", "Local");
    return path.join(localAppData, "Coursier", "Cache", "v1");
  }

  // Linux and others
  return path.join(home, ".cache", "coursier", "v1");
}

/**
 * Extracts the repository URL from POM XML content using regex.
 *
 * Priority:
 * 1. `<scm><url>` — the source repository URL
 * 2. Top-level `<url>` — only if it looks like a known repository host
 *
 * Cleans `scm:git:` prefix, `.git` suffix, normalizes `http://` → `https://`.
 */
export function extractRepositoryUrl(pomXml: string): string | undefined {
  // Try <scm><url> first
  const scmMatch = /<scm>[\s\S]*?<url>(.*?)<\/url>[\s\S]*?<\/scm>/.exec(pomXml);
  if (scmMatch?.[1]) {
    const cleaned = cleanUrl(scmMatch[1]);
    if (cleaned) return cleaned;
  }

  // Fall back to top-level <url> if it looks like a repo host
  const urlMatch = /<url>(.*?)<\/url>/.exec(pomXml);
  if (urlMatch?.[1]) {
    const url = urlMatch[1];
    const repoHosts = ["github.com", "gitlab.com", "bitbucket.org", "codeberg.org"];
    if (repoHosts.some((host) => url.includes(host))) {
      return cleanUrl(url);
    }
  }

  return undefined;
}

function cleanUrl(url: string): string | undefined {
  let cleaned = url.trim();
  if (!cleaned) return undefined;

  // Strip scm:git: prefix (and variants like scm:git|ssh:)
  cleaned = cleaned.replace(/^scm:git:[^/]*(?=https?:\/\/)/, "");
  cleaned = cleaned.replace(/^scm:git:/, "");

  // Strip .git suffix
  cleaned = cleaned.replace(/\.git$/, "");

  // Normalize http to https
  cleaned = cleaned.replace(/^http:\/\//, "https://");

  // Only return valid https URLs — reject git://, ssh://, git@, etc.
  if (!cleaned.startsWith("https://")) return undefined;

  return cleaned;
}

/**
 * Finds all Maven layout base paths within a repo host directory using BFS.
 *
 * Different repositories use different sub-paths before the Maven layout starts:
 * - `repo1.maven.org` → `maven2/`
 * - `oss.sonatype.org` → `content/repositories/releases/`
 *
 * Some hosts (e.g. JFrog Artifactory) serve multiple Maven repositories under
 * different paths.  This function returns all of them so the caller can try each.
 *
 * Searches up to 4 levels deep for directories containing the first component
 * of the org path (e.g., `org` for `org.typelevel`).
 */
export function findMavenBases(
  hostDir: string,
  orgFirstComponent: string,
  readdir: ReadDir
): string[] {
  const results: string[] = [];
  const queue: Array<{ dir: string; depth: number }> = [{ dir: hostDir, depth: 0 }];

  while (queue.length > 0) {
    const { dir, depth } = queue.shift()!;

    let entries: string[];
    try {
      entries = readdir(dir);
    } catch {
      continue;
    }

    if (entries.includes(orgFirstComponent)) {
      results.push(dir);
      continue;
    }

    if (depth < 4) {
      for (const entry of entries) {
        queue.push({ dir: path.join(dir, entry), depth: depth + 1 });
      }
    }
  }

  return results;
}

/** Cache for `findMavenBases` results keyed by `hostPath\0orgFirstComponent`. */
const mavenBaseCache = new Map<string, string[]>();

/**
 * Resolves the project repository URL for a dependency by searching
 * POM files in the Coursier cache.
 *
 * Searches all repositories in the cache, trying multiple artifact name
 * suffixes for Scala dependencies.  Maven-base lookups are cached so
 * that repeated calls for dependencies with the same org prefix are fast.
 *
 * Returns `undefined` if no POM with a repository URL is found.
 */
export function resolveRepositoryUrl(
  dep: DependencyMatch,
  cachePath?: string,
  readdir?: ReadDir,
  readFile?: ReadFile
): string | undefined {
  const cache = cachePath ?? getCoursierCachePath();
  const rd: ReadDir = readdir ?? defaultReaddir;
  const rf: ReadFile = readFile ?? defaultReadFile;

  const isScala = dep.separator === "::";
  const isSbtPlugin = dep.config === "sbt-plugin";

  // Build artifact name candidates
  let candidates: string[];
  if (isSbtPlugin) {
    candidates = [`${dep.artifact}_2.12_1.0`];
  } else if (isScala) {
    candidates = [`${dep.artifact}_3`, `${dep.artifact}_2.13`];
  } else {
    candidates = [dep.artifact];
  }

  const orgPath = dep.org.split(".").join("/");
  const orgFirstComponent = dep.org.split(".")[0];

  // List all host directories under {cache}/https/
  const httpsDir = path.join(cache, "https");
  let hostDirs: string[];
  try {
    hostDirs = rd(httpsDir);
  } catch {
    return undefined;
  }

  for (const hostDir of hostDirs) {
    const hostPath = path.join(httpsDir, hostDir);

    // Cache findMavenBases results to avoid repeated BFS for every dependency
    const cacheKey = `${hostPath}\0${orgFirstComponent}`;
    let mavenBases: string[];
    if (mavenBaseCache.has(cacheKey)) {
      mavenBases = mavenBaseCache.get(cacheKey)!;
    } else {
      mavenBases = findMavenBases(hostPath, orgFirstComponent, rd);
      mavenBaseCache.set(cacheKey, mavenBases);
    }
    if (mavenBases.length === 0) continue;

    for (const mavenBase of mavenBases) {
      for (const candidate of candidates) {
        const artifactDir = path.join(mavenBase, orgPath, candidate);

        let versions: string[];
        try {
          versions = rd(artifactDir);
        } catch {
          continue;
        }

        for (const version of versions) {
          const versionDir = path.join(artifactDir, version);

          let files: string[];
          try {
            files = rd(versionDir);
          } catch {
            continue;
          }

          const pomFile = files.find((f) => f.endsWith(".pom"));
          if (!pomFile) continue;

          const pomContent = rf(path.join(versionDir, pomFile));
          if (!pomContent) continue;

          const url = extractRepositoryUrl(pomContent);
          if (url) return url;
        }
      }
    }
  }

  return undefined;
}

function defaultReaddir(dir: string): string[] {
  const fs = require("node:fs");
  return fs.readdirSync(dir) as string[];
}

function defaultReadFile(file: string): string | undefined {
  const fs = require("node:fs");
  try {
    return fs.readFileSync(file, "utf-8") as string;
  } catch {
    return undefined;
  }
}
