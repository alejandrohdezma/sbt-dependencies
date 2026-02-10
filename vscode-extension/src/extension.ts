import * as vscode from "vscode";
import { parseDiagnostics } from "./diagnostics";
import { parseDependency, buildHoverMarkdown } from "./hover";

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
    const dep = parseDependency(text);

    if (dep) {
      const isScala = dep.separator === "::";
      const isSbtPlugin = dep.config === "sbt-plugin";
      checkAvailability(dep.org, dep.artifact, isScala, isSbtPlugin);
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
    const text = document.lineAt(position.line).text;
    const dep = parseDependency(text);

    if (!dep) return undefined;

    if (position.character < dep.matchStart || position.character > dep.matchEnd) return undefined;

    const isScala = dep.separator === "::";
    const isSbtPlugin = dep.config === "sbt-plugin";
    const available = await checkAvailability(dep.org, dep.artifact, isScala, isSbtPlugin);

    const md = new vscode.MarkdownString();
    md.isTrusted = true;
    md.appendMarkdown(buildHoverMarkdown(dep, available));

    const matchRange = new vscode.Range(
      position.line, dep.matchStart,
      position.line, dep.matchEnd
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
