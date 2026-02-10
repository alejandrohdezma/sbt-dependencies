import * as vscode from "vscode";
import { parseDiagnostics } from "./diagnostics";

/**
 * Matches dependency declarations in the form `org::artifact:version`.
 *
 * - Group 1: organization (e.g. `org.typelevel`)
 * - Group 2: separator (`:` for Java, `::` for Scala)
 * - Group 3: artifact name (e.g. `cats-core`)
 * - Group 4: version (e.g. `^2.10.0`), if present
 * - Group 5: configuration (e.g. `sbt-plugin`), if present
 */
const dependencyPattern =
  /([^\s:"]+)(::?)([^\s:"]+)(?::(\{\{\w+\}\}|[=^~]?\d[^\s:"]*)(?::([^\s:"]+))?)?/g;

/**
 * Scans a `dependencies.conf` document for malformed dependency strings
 * and publishes diagnostics.
 */
function updateDiagnostics(
  document: vscode.TextDocument,
  collection: vscode.DiagnosticCollection
): void {
  if (document.languageId !== "sbt-dependencies") {
    return;
  }

  const lines: string[] = [];
  for (let i = 0; i < document.lineCount; i++) {
    lines.push(document.lineAt(i).text);
  }

  const results = parseDiagnostics(lines);
  const diagnostics = results.map((r) => {
    const range = new vscode.Range(r.range.startLine, r.range.startCol, r.range.endLine, r.range.endCol);
    const d = new vscode.Diagnostic(range, r.message, vscode.DiagnosticSeverity.Error);
    d.source = r.source;
    return d;
  });

  collection.set(document.uri, diagnostics);
}

/** Cache of Maven Central availability checks. */
const availabilityCache = new Map<string, boolean>();

/**
 * Checks whether a dependency is available on Maven Central by sending HEAD
 * requests to `repo1.maven.org`.
 *
 * For Scala dependencies (`::`) it tries both `_3` and `_2.13` suffixes,
 * returning `true` if either exists.
 *
 * Results are cached. On network errors the result is not cached and the
 * dependency is assumed available so the link still shows.
 */
async function checkAvailability(
  org: string,
  artifact: string,
  isScala: boolean,
  isSbtPlugin: boolean
): Promise<boolean> {
  const cacheKey = `${org}:${artifact}:${isScala}:${isSbtPlugin}`;
  const cached = availabilityCache.get(cacheKey);
  if (cached !== undefined) return cached;

  const orgPath = org.split(".").join("/");
  const suffixes = isSbtPlugin ? ["_2.12_1.0"] : isScala ? ["_3", "_2.13"] : [""];

  try {
    for (const suffix of suffixes) {
      const url = `https://repo1.maven.org/maven2/${orgPath}/${artifact}${suffix}/`;
      const response = await fetch(url, { method: "HEAD" });
      if (response.ok) {
        availabilityCache.set(cacheKey, true);
        return true;
      }
    }
    availabilityCache.set(cacheKey, false);
    return false;
  } catch {
    return true;
  }
}

/**
 * Scans a document for dependencies and pre-checks their availability on
 * Maven Central, populating the cache so hovers can read it synchronously.
 */
function warmAvailabilityCache(document: vscode.TextDocument): void {
  if (document.languageId !== "sbt-dependencies") return;

  for (let i = 0; i < document.lineCount; i++) {
    const text = document.lineAt(i).text;

    dependencyPattern.lastIndex = 0;
    const match = dependencyPattern.exec(text);

    if (match) {
      const org = match[1];
      const artifact = match[3];
      const isScala = match[2] === "::";
      const isSbtPlugin = match[5] === "sbt-plugin";
      checkAvailability(org, artifact, isScala, isSbtPlugin);
    }
  }
}

/**
 * Provides hover tooltips for dependencies found in `dependencies.conf` files,
 * showing organization, artifact, version marker explanation, configuration,
 * and a link to mvnrepository.com.
 */
class DependencyHoverProvider implements vscode.HoverProvider {
  async provideHover(
    document: vscode.TextDocument,
    position: vscode.Position
  ): Promise<vscode.Hover | undefined> {
    const line = document.lineAt(position.line);
    const text = line.text;

    dependencyPattern.lastIndex = 0;
    const match = dependencyPattern.exec(text);

    if (!match || match.index === undefined) return undefined;

    const matchStart = match.index;
    const matchEnd = matchStart + match[0].length;

    if (position.character < matchStart || position.character > matchEnd) return undefined;

    const org = match[1];
    const separator = match[2];
    const artifact = match[3];
    const version = match[4];
    const config = match[5];
    const isSbtPlugin = config === "sbt-plugin";
    const isScala = separator === "::";
    const artifactForUrl = isSbtPlugin ? `${artifact}_2.12_1.0` : artifact;
    const url = `https://mvnrepository.com/artifact/${org}/${artifactForUrl}`;

    const available = await checkAvailability(org, artifact, isScala, isSbtPlugin);

    const md = new vscode.MarkdownString();
    md.isTrusted = true;

    md.appendMarkdown(`**${org}** \`${separator}\` **${artifact}**\n\n`);

    if (version) {
      let explanation: string;
      if (version.startsWith("{{")) {
        explanation = "resolved from variable";
      } else if (version.startsWith("=")) {
        explanation = "pinned";
      } else if (version.startsWith("^")) {
        explanation = "update within major";
      } else if (version.startsWith("~")) {
        explanation = "update within minor";
      } else {
        explanation = "update to latest";
      }
      md.appendMarkdown(`Version: \`${version}\` *(${explanation})*`);
    } else {
      md.appendMarkdown(`Version: *resolved to latest*`);
    }

    if (config) {
      md.appendMarkdown(`\\\nConfiguration: \`${config}\``);
    }

    if (available) {
      md.appendMarkdown(`\n\n[Open on mvnrepository](${url})`);
    }

    const matchRange = new vscode.Range(
      position.line, matchStart,
      position.line, matchEnd
    );

    return new vscode.Hover(md, matchRange);
  }
}

/** Registers the hover provider and diagnostics. */
export function activate(context: vscode.ExtensionContext): void {
  context.subscriptions.push(
    vscode.languages.registerHoverProvider(
      "sbt-dependencies",
      new DependencyHoverProvider()
    )
  );

  const diagnostics = vscode.languages.createDiagnosticCollection("sbt-dependencies");
  context.subscriptions.push(diagnostics);

  context.subscriptions.push(
    vscode.workspace.onDidOpenTextDocument(doc => {
      updateDiagnostics(doc, diagnostics);
      warmAvailabilityCache(doc);
    }),
    vscode.workspace.onDidChangeTextDocument(e => {
      updateDiagnostics(e.document, diagnostics);
      warmAvailabilityCache(e.document);
    }),
    vscode.workspace.onDidCloseTextDocument(doc => diagnostics.delete(doc.uri))
  );

  vscode.workspace.textDocuments.forEach(doc => {
    updateDiagnostics(doc, diagnostics);
    warmAvailabilityCache(doc);
  });
}

export function deactivate(): void {}
