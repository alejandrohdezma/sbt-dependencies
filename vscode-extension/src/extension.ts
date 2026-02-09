import * as vscode from "vscode";

/**
 * Matches dependency declarations in the form `org::artifact:version`.
 *
 * - Group 1: organization (e.g. `org.typelevel`)
 * - Group 2: separator (`:` for Java, `::` for Scala)
 * - Group 3: artifact name (e.g. `cats-core`)
 */
const dependencyPattern =
  /([^\s:"]+)(::?)([^\s:"]+)(?::(?:\{\{\w+\}\}|[=^~]?\d[^\s:"]*)(?::[^\s:"]+)?)?/g;

/** Cache of Maven Central availability checks to avoid redundant network requests. */
const urlCache = new Map<string, boolean>();

/**
 * Checks whether a dependency is available on Maven Central by sending HEAD
 * requests to `repo1.maven.org`.
 *
 * For Scala dependencies (`::`) it tries both `_3` and `_2.13` suffixes,
 * returning `true` if either exists.
 *
 * Results are cached. On network errors the result is not cached and the
 * dependency is assumed available so the CodeLens link still shows.
 */
async function isAvailable(
  org: string,
  artifact: string,
  isScala: boolean
): Promise<boolean> {
  const cacheKey = `${org}:${artifact}:${isScala}`;
  const cached = urlCache.get(cacheKey);
  if (cached !== undefined) return cached;

  const orgPath = org.split(".").join("/");
  const suffixes = isScala ? ["_3", "_2.13"] : [""];

  try {
    for (const suffix of suffixes) {
      const url = `https://repo1.maven.org/maven2/${orgPath}/${artifact}${suffix}/`;
      const response = await fetch(url, { method: "HEAD" });
      if (response.ok) {
        urlCache.set(cacheKey, true);
        return true;
      }
    }
    urlCache.set(cacheKey, false);
    return false;
  } catch {
    // Network error — don't cache, assume available so the link still shows
    return true;
  }
}

/**
 * Provides CodeLens links to mvnrepository.com for dependencies found in
 * `dependencies.conf` files. Only shows links for dependencies that exist
 * on Maven Central.
 */
class DependencyCodeLensProvider implements vscode.CodeLensProvider {
  async provideCodeLenses(
    document: vscode.TextDocument
  ): Promise<vscode.CodeLens[]> {
    const candidates: { range: vscode.Range; url: string; org: string; artifact: string; isScala: boolean }[] = [];

    for (let i = 0; i < document.lineCount; i++) {
      const line = document.lineAt(i);
      const text = line.text;

      dependencyPattern.lastIndex = 0;
      const match = dependencyPattern.exec(text);

      if (match) {
        const org = match[1];
        const artifact = match[3];
        const isScala = match[2] === "::";
        const url = `https://mvnrepository.com/artifact/${org}/${artifact}`;
        candidates.push({ range: line.range, url, org, artifact, isScala });
      }
    }

    const results = await Promise.all(
      candidates.map(async (c) => ({
        ...c,
        available: await isAvailable(c.org, c.artifact, c.isScala),
      }))
    );

    return results
      .filter((c) => c.available)
      .map(
        (c) =>
          new vscode.CodeLens(c.range, {
            title: "$(link-external) Open on mvnrepository",
            command: "sbt-dependencies.openMvnRepository",
            arguments: [c.url],
          })
      );
  }
}

/** Registers the CodeLens provider and the command to open mvnrepository links. */
export function activate(context: vscode.ExtensionContext): void {
  context.subscriptions.push(
    vscode.commands.registerCommand(
      "sbt-dependencies.openMvnRepository",
      (url: string) => vscode.env.openExternal(vscode.Uri.parse(url))
    )
  );

  context.subscriptions.push(
    vscode.languages.registerCodeLensProvider(
      "sbt-dependencies",
      new DependencyCodeLensProvider()
    )
  );
}

export function deactivate(): void {}
