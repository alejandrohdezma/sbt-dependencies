import * as vscode from "vscode";
import { parseDiagnostics } from "./diagnostics";
import { parseDependency, buildHoverMarkdown } from "./hover";
import { findReferences } from "./references";
import { parseDocumentSymbols } from "./symbols";

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

/**
 * Provides document symbols (Outline / breadcrumbs) for `dependencies.conf` files,
 * showing groups as namespaces and their dependencies as packages.
 */
class DependencyDocumentSymbolProvider implements vscode.DocumentSymbolProvider {
  provideDocumentSymbols(document: vscode.TextDocument): vscode.DocumentSymbol[] {
    const lines: string[] = [];
    for (let i = 0; i < document.lineCount; i++) {
      lines.push(document.lineAt(i).text);
    }

    return parseDocumentSymbols(lines).map((group) => {
      const groupRange = new vscode.Range(
        group.range.startLine, group.range.startCol,
        group.range.endLine, group.range.endCol
      );
      const groupSelection = new vscode.Range(
        group.range.startLine, group.range.startCol,
        group.range.startLine, group.range.startCol + group.name.length
      );
      const groupSymbol = new vscode.DocumentSymbol(
        group.name, "", vscode.SymbolKind.Namespace, groupRange, groupSelection
      );

      groupSymbol.children = (group.children ?? []).map((dep) => {
        const depRange = new vscode.Range(
          dep.range.startLine, dep.range.startCol,
          dep.range.endLine, dep.range.endCol
        );
        return new vscode.DocumentSymbol(
          dep.name, "", vscode.SymbolKind.Package, depRange, depRange
        );
      });

      return groupSymbol;
    });
  }
}

/**
 * Provides "Find All References" for variables (`{{varName}}`) and
 * dependencies (`org::artifact`) in `dependencies.conf` files.
 */
class DependencyReferenceProvider implements vscode.ReferenceProvider {
  provideReferences(
    document: vscode.TextDocument,
    position: vscode.Position
  ): vscode.Location[] | undefined {
    const lines: string[] = [];
    for (let i = 0; i < document.lineCount; i++) {
      lines.push(document.lineAt(i).text);
    }

    const refs = findReferences(lines, position.line, position.character);
    if (!refs) return undefined;

    return refs.map(
      (r) =>
        new vscode.Location(
          document.uri,
          new vscode.Range(r.line, r.startCol, r.line, r.endCol)
        )
    );
  }
}

/**
 * Returns an existing terminal named `"sbt-dependencies"` or creates a new
 * one with `cwd` set to the first workspace folder.
 */
function getSbtTerminal(): vscode.Terminal {
  const existing = vscode.window.terminals.find(t => t.name === "sbt-dependencies");
  if (existing) return existing;

  const cwd = vscode.workspace.workspaceFolders?.[0]?.uri;
  return vscode.window.createTerminal({ name: "sbt-dependencies", cwd });
}

function runUpdateAllDependencies(): void {
  if (!vscode.workspace.workspaceFolders) {
    vscode.window.showErrorMessage("No workspace folder open.");
    return;
  }
  const terminal = getSbtTerminal();
  terminal.show();
  terminal.sendText("sbtn updateAllDependencies");
}

function runUpdateDependencies(): void {
  if (!vscode.workspace.workspaceFolders) {
    vscode.window.showErrorMessage("No workspace folder open.");
    return;
  }
  const terminal = getSbtTerminal();
  terminal.show();
  terminal.sendText("sbtn updateDependencies");
}

function runUpdateSpecificDependency(org: string, artifact: string): void {
  if (!vscode.workspace.workspaceFolders) {
    vscode.window.showErrorMessage("No workspace folder open.");
    return;
  }
  const terminal = getSbtTerminal();
  terminal.show();
  terminal.sendText(`sbtn updateDependencies ${org}:${artifact}`);
}

/**
 * Returns the correct sbtn command for installing a dependency in a group.
 * The `sbt-build` group uses a separate global command.
 */
function getInstallCommand(groupName: string, dependency: string): string {
  if (groupName === "sbt-build") {
    return `sbtn installBuildDependencies ${dependency}`;
  }
  return `sbtn ${groupName}/install ${dependency}`;
}

/**
 * Command Palette handler: prompts the user to pick a group and enter a
 * dependency string, then runs the install command in the SBT terminal.
 */
async function runInstallDependency(): Promise<void> {
  if (!vscode.workspace.workspaceFolders) {
    vscode.window.showErrorMessage("No workspace folder open.");
    return;
  }

  const editor = vscode.window.activeTextEditor;
  if (!editor || editor.document.languageId !== "sbt-dependencies") {
    vscode.window.showErrorMessage("Open a dependencies.conf file first.");
    return;
  }

  const lines: string[] = [];
  for (let i = 0; i < editor.document.lineCount; i++) {
    lines.push(editor.document.lineAt(i).text);
  }

  const groupNames = parseDocumentSymbols(lines).map((g) => g.name);
  if (groupNames.length === 0) {
    vscode.window.showErrorMessage("No dependency groups found in the current file.");
    return;
  }

  const group = await vscode.window.showQuickPick(groupNames, {
    placeHolder: "Select the dependency group",
  });
  if (!group) return;

  const dependency = await vscode.window.showInputBox({
    prompt: `Enter the dependency to install in '${group}'`,
    placeHolder: "org.typelevel::cats-core:2.10.0",
  });
  if (!dependency) return;

  const terminal = getSbtTerminal();
  terminal.show();
  terminal.sendText(getInstallCommand(group, dependency));
}

/**
 * Code Action handler: prompts the user for a dependency string and runs the
 * install command for the given group.
 */
async function runInstallDependencyInGroup(groupName: string): Promise<void> {
  if (!vscode.workspace.workspaceFolders) {
    vscode.window.showErrorMessage("No workspace folder open.");
    return;
  }

  const dependency = await vscode.window.showInputBox({
    prompt: `Enter the dependency to install in '${groupName}'`,
    placeHolder: "org.typelevel::cats-core:2.10.0",
  });
  if (!dependency) return;

  const terminal = getSbtTerminal();
  terminal.show();
  terminal.sendText(getInstallCommand(groupName, dependency));
}

/**
 * Provides code actions to update individual dependencies via the SBT plugin.
 */
class DependencyCodeActionProvider implements vscode.CodeActionProvider {
  provideCodeActions(
    document: vscode.TextDocument,
    range: vscode.Range | vscode.Selection
  ): vscode.CodeAction[] | undefined {
    const line = document.lineAt(range.start.line).text;
    const dep = parseDependency(line);

    if (dep && range.start.character >= dep.matchStart && range.start.character <= dep.matchEnd) {
      const action = new vscode.CodeAction(
        `Update ${dep.org}:${dep.artifact}`,
        vscode.CodeActionKind.RefactorRewrite
      );
      action.command = {
        command: "sbt-dependencies.updateSpecificDependency",
        title: `Update ${dep.org}:${dep.artifact}`,
        arguments: [dep.org, dep.artifact],
      };
      return [action];
    }

    // Check if cursor is on a group header line
    const simpleMatch = /^(\s*)([\w][\w.-]*)\s*=\s*\[/.exec(line);
    const advancedMatch = /^(\s*)([\w][\w.-]*)\s*\{/.exec(line);
    const groupName = simpleMatch?.[2] ?? advancedMatch?.[2];

    if (groupName) {
      const action = new vscode.CodeAction(
        `Install dependency in '${groupName}'`,
        vscode.CodeActionKind.RefactorRewrite
      );
      action.command = {
        command: "sbt-dependencies.installDependencyInGroup",
        title: `Install dependency in '${groupName}'`,
        arguments: [groupName],
      };
      return [action];
    }

    return undefined;
  }
}

/** Registers providers, commands, and diagnostics. */
export function activate(context: vscode.ExtensionContext): void {
  context.subscriptions.push(
    vscode.languages.registerHoverProvider(
      "sbt-dependencies",
      new DependencyHoverProvider()
    ),
    vscode.languages.registerDocumentSymbolProvider(
      "sbt-dependencies",
      new DependencyDocumentSymbolProvider()
    ),
    vscode.languages.registerReferenceProvider(
      "sbt-dependencies",
      new DependencyReferenceProvider()
    ),
    vscode.languages.registerCodeActionsProvider(
      "sbt-dependencies",
      new DependencyCodeActionProvider()
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.updateAllDependencies",
      runUpdateAllDependencies
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.updateDependencies",
      runUpdateDependencies
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.updateSpecificDependency",
      runUpdateSpecificDependency
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.installDependency",
      runInstallDependency
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.installDependencyInGroup",
      runInstallDependencyInGroup
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
