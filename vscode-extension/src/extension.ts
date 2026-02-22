import * as fs from "node:fs";
import * as vscode from "vscode";
import { parseCodeLenses } from "./codelens";
import { parsePinnedWithoutNote } from "./dep-codelens";
import { parseDiagnostics } from "./diagnostics";
import { formatDocument } from "./formatting";
import { parseDependency, buildHoverMarkdown } from "./hover";
import { parseDocumentLinks } from "./links";
import { parseNoteDecorations } from "./note-decorations";
import { resolveRepositoryUrl } from "./pom";
import { findReferences } from "./references";
import { getQuickFixes } from "./quickfix";
import { prepareVariableRename, computeVariableRenameEdits } from "./rename";
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
    const severity = r.severity === "warning" ? vscode.DiagnosticSeverity.Warning : vscode.DiagnosticSeverity.Error;
    const d = new vscode.Diagnostic(range, r.message, severity);
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

/** Cache of repository URL lookups from Coursier POM files. */
const repoUrlCache = new Map<string, string | undefined>();

/**
 * Resolves and caches the project repository URL for a dependency by
 * reading POM files in the Coursier cache.
 */
function resolveAndCacheRepoUrl(dep: import("./hover").DependencyMatch): string | undefined {
  const cacheKey = `${dep.org}:${dep.artifact}:${dep.separator}:${dep.config ?? ""}`;
  if (repoUrlCache.has(cacheKey)) return repoUrlCache.get(cacheKey);

  const url = resolveRepositoryUrl(dep);
  repoUrlCache.set(cacheKey, url);
  return url;
}

/**
 * Scans a document for dependencies and pre-resolves their repository URLs
 * from the Coursier cache, populating the repoUrlCache.
 */
function warmRepoUrlCache(document: vscode.TextDocument): void {
  if (document.languageId !== "sbt-dependencies") return;

  for (let i = 0; i < document.lineCount; i++) {
    const text = document.lineAt(i).text;
    const dep = parseDependency(text);

    if (dep) {
      resolveAndCacheRepoUrl(dep);
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
 * Provides Cmd+Clickable links for dependencies in `dependencies.conf`
 * files.  When a project repository URL is found in the Coursier cache
 * it links there; otherwise it falls back to mvnrepository.com.
 */
class DependencyDocumentLinkProvider implements vscode.DocumentLinkProvider {
  async provideDocumentLinks(
    document: vscode.TextDocument
  ): Promise<vscode.DocumentLink[]> {
    const lines: string[] = [];
    for (let i = 0; i < document.lineCount; i++) {
      lines.push(document.lineAt(i).text);
    }

    const parsed = parseDocumentLinks(lines, resolveAndCacheRepoUrl);
    const results: vscode.DocumentLink[] = [];

    for (const link of parsed) {
      const isMvnRepository = link.url.includes("mvnrepository.com");

      // When the link points to a repo URL (not mvnrepository), skip the
      // availability check — its presence in the cache proves the artifact
      // exists.
      if (isMvnRepository) {
        const dep = parseDependency(document.lineAt(link.range.startLine).text);
        if (!dep) continue;

        const isScala = dep.separator === "::";
        const isSbtPlugin = dep.config === "sbt-plugin";
        const available = await checkAvailability(dep.org, dep.artifact, isScala, isSbtPlugin);
        if (!available) continue;
      }

      const range = new vscode.Range(
        link.range.startLine, link.range.startCol,
        link.range.endLine, link.range.endCol
      );
      results.push(new vscode.DocumentLink(range, vscode.Uri.parse(link.url)));
    }

    return results;
  }
}

/**
 * Provides rename support for `{{varName}}` tokens in `dependencies.conf`
 * files.  F2 on a variable renames all occurrences in the document.
 */
class DependencyRenameProvider implements vscode.RenameProvider {
  prepareRename(
    document: vscode.TextDocument,
    position: vscode.Position
  ): vscode.Range | undefined {
    const lines: string[] = [];
    for (let i = 0; i < document.lineCount; i++) {
      lines.push(document.lineAt(i).text);
    }

    const range = prepareVariableRename(lines, position.line, position.character);
    if (!range) return undefined;

    return new vscode.Range(range.startLine, range.startCol, range.endLine, range.endCol);
  }

  provideRenameEdits(
    document: vscode.TextDocument,
    position: vscode.Position,
    newName: string
  ): vscode.WorkspaceEdit | undefined {
    const lines: string[] = [];
    for (let i = 0; i < document.lineCount; i++) {
      lines.push(document.lineAt(i).text);
    }

    const result = computeVariableRenameEdits(lines, position.line, position.character, newName);
    if (!result) return undefined;

    const edit = new vscode.WorkspaceEdit();
    for (const e of result.edits) {
      edit.replace(
        document.uri,
        new vscode.Range(e.line, e.startCol, e.line, e.endCol),
        e.newText
      );
    }
    return edit;
  }
}

/**
 * Provides document formatting for `dependencies.conf` files, sorting
 * dependencies alphabetically within groups and normalizing indentation.
 */
class DependencyDocumentFormattingProvider implements vscode.DocumentFormattingEditProvider {
  provideDocumentFormattingEdits(
    document: vscode.TextDocument
  ): vscode.TextEdit[] {
    const lines: string[] = [];
    for (let i = 0; i < document.lineCount; i++) {
      lines.push(document.lineAt(i).text);
    }

    const formatted = formatDocument(lines);
    const fullRange = new vscode.Range(
      0, 0,
      document.lineCount - 1, document.lineAt(document.lineCount - 1).text.length
    );

    return [vscode.TextEdit.replace(fullRange, formatted)];
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
    range: vscode.Range | vscode.Selection,
    context: vscode.CodeActionContext
  ): vscode.CodeAction[] | undefined {
    const actions: vscode.CodeAction[] = [];

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
      actions.push(action);
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
      actions.push(action);
    }

    for (const diagnostic of context.diagnostics) {
      const fixes = getQuickFixes(diagnostic.message, diagnostic.range.start.line);
      for (const fix of fixes) {
        const action = new vscode.CodeAction(fix.title, vscode.CodeActionKind.QuickFix);
        action.isPreferred = true;
        action.diagnostics = [diagnostic];
        const edit = new vscode.WorkspaceEdit();
        edit.delete(document.uri, document.lineAt(fix.deleteLineIndex).rangeIncludingLineBreak);
        action.edit = edit;
        actions.push(action);
      }
    }

    return actions.length > 0 ? actions : undefined;
  }
}

/**
 * Provides CodeLens annotations on `lazy val ... = project` lines in `.sbt`
 * files, linking each project to its group in `dependencies.conf`.
 */
class SbtBuildCodeLensProvider implements vscode.CodeLensProvider {
  provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
    const buildSbtLines: string[] = [];
    for (let i = 0; i < document.lineCount; i++) {
      buildSbtLines.push(document.lineAt(i).text);
    }

    const depsConfUri = vscode.Uri.joinPath(
      document.uri,
      "..",
      "project",
      "dependencies.conf"
    );

    let groupLineMap = new Map<string, number>();
    try {
      const content = fs.readFileSync(depsConfUri.fsPath, "utf-8");
      const confLines: string[] = content.split(/\r?\n/);
      for (const symbol of parseDocumentSymbols(confLines)) {
        groupLineMap.set(symbol.name, symbol.range.startLine);
      }
    } catch {
      // dependencies.conf doesn't exist or can't be read
    }

    const groupNames = Array.from(groupLineMap.keys());
    const codeLensDataList = parseCodeLenses(buildSbtLines, groupNames);

    return codeLensDataList
      .filter((data) => data.groupExists)
      .map((data) => {
        const range = new vscode.Range(data.line, 0, data.line, 0);

        return new vscode.CodeLens(range, {
          title: "View dependencies",
          command: "sbt-dependencies.openDependenciesGroup",
          arguments: [depsConfUri, groupLineMap.get(data.projectName)],
        });
      });
  }
}

/**
 * Provides CodeLens annotations on group headers in `dependencies.conf`
 * files, linking each group to its project definition in `build.sbt`.
 */
class DependencyGroupCodeLensProvider implements vscode.CodeLensProvider {
  provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
    const confLines: string[] = [];
    for (let i = 0; i < document.lineCount; i++) {
      confLines.push(document.lineAt(i).text);
    }

    const buildSbtUri = vscode.Uri.joinPath(
      document.uri,
      "..",
      "..",
      "build.sbt"
    );

    let projectLineMap = new Map<string, number>();
    try {
      const content = fs.readFileSync(buildSbtUri.fsPath, "utf-8");
      const buildLines: string[] = content.split(/\r?\n/);
      for (const data of parseCodeLenses(buildLines, [])) {
        projectLineMap.set(data.projectName, data.line);
      }
    } catch {
      // build.sbt doesn't exist or can't be read
    }

    const groups = parseDocumentSymbols(confLines);

    return groups
      .filter((group) => projectLineMap.has(group.name))
      .map((group) => {
        const range = new vscode.Range(
          group.range.startLine, 0,
          group.range.startLine, 0
        );

        return new vscode.CodeLens(range, {
          title: "View project",
          command: "sbt-dependencies.openBuildProject",
          arguments: [buildSbtUri, projectLineMap.get(group.name)],
        });
      });
  }
}

async function openDependenciesGroup(
  fileUri: vscode.Uri,
  lineNumber: number | undefined
): Promise<void> {
  try {
    const document = await vscode.workspace.openTextDocument(fileUri);
    const line = lineNumber ?? Math.max(0, document.lineCount - 1);
    const position = new vscode.Position(line, 0);
    await vscode.window.showTextDocument(document, {
      selection: new vscode.Range(position, position),
      viewColumn: vscode.ViewColumn.Active,
    });
    vscode.commands.executeCommand("revealLine", {
      lineNumber: line,
      at: "center",
    });
  } catch {
    vscode.window.showErrorMessage(
      `Could not open ${fileUri.fsPath}`
    );
  }
}

async function openBuildProject(
  fileUri: vscode.Uri,
  lineNumber: number
): Promise<void> {
  try {
    const document = await vscode.workspace.openTextDocument(fileUri);
    const position = new vscode.Position(lineNumber, 0);
    await vscode.window.showTextDocument(document, {
      selection: new vscode.Range(position, position),
      viewColumn: vscode.ViewColumn.Active,
    });
    vscode.commands.executeCommand("revealLine", {
      lineNumber,
      at: "center",
    });
  } catch {
    vscode.window.showErrorMessage(
      `Could not open ${fileUri.fsPath}`
    );
  }
}

/**
 * Converts a plain pinned dependency string into object form with an empty
 * note, then places the cursor inside the note quotes.
 *
 * For single-line object entries (e.g. intransitive), inserts a `note = ""`
 * field after `dependency` and places the cursor inside the quotes.
 *
 * For multi-line object entries, inserts a `note = ""` line after the
 * `dependency` line and places the cursor inside the quotes.
 */
async function addDependencyNote(line: number): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  if (!editor) return;

  const lineText = editor.document.lineAt(line).text;

  // Case 1: Plain string entry — wrap in object form
  const plainMatch = /^(\s*)"(.*)"(\s*)$/.exec(lineText);
  if (plainMatch) {
    const indent = plainMatch[1];
    const depString = plainMatch[2];
    const replacement = `${indent}{ dependency = "${depString}", note = "" }`;

    const fullLineRange = editor.document.lineAt(line).range;
    await editor.edit((editBuilder) => {
      editBuilder.replace(fullLineRange, replacement);
    });

    const cursorCol = replacement.indexOf('note = "') + 'note = "'.length;
    const cursorPos = new vscode.Position(line, cursorCol);
    editor.selection = new vscode.Selection(cursorPos, cursorPos);
    return;
  }

  // Case 2: Single-line object entry — insert note field after dependency
  const singleLineObjMatch = /^(\s*)\{(.*dependency\s*=\s*"[^"]*")(.*)(\})/.exec(lineText);
  if (singleLineObjMatch) {
    const indent = singleLineObjMatch[1];
    const depPart = singleLineObjMatch[2];
    const rest = singleLineObjMatch[3];
    const replacement = `${indent}{${depPart}, note = ""${rest}}`;

    const fullLineRange = editor.document.lineAt(line).range;
    await editor.edit((editBuilder) => {
      editBuilder.replace(fullLineRange, replacement);
    });

    const cursorCol = replacement.indexOf('note = "') + 'note = "'.length;
    const cursorPos = new vscode.Position(line, cursorCol);
    editor.selection = new vscode.Selection(cursorPos, cursorPos);
    return;
  }

  // Case 3: Multi-line object — find the dependency line and insert note after it
  for (let i = line; i < editor.document.lineCount; i++) {
    const currentLine = editor.document.lineAt(i).text;
    const depFieldMatch = /^(\s*)dependency\s*=\s*"[^"]*"/.exec(currentLine);
    if (depFieldMatch) {
      const fieldIndent = depFieldMatch[1];
      const noteLineText = `${fieldIndent}note = ""`;
      const insertPos = new vscode.Position(i + 1, 0);
      await editor.edit((editBuilder) => {
        editBuilder.insert(insertPos, noteLineText + "\n");
      });

      const cursorCol = noteLineText.indexOf('note = "') + 'note = "'.length;
      const cursorPos = new vscode.Position(i + 1, cursorCol);
      editor.selection = new vscode.Selection(cursorPos, cursorPos);
      return;
    }
    // Stop if we hit the closing brace without finding dependency
    if (currentLine.includes("}")) break;
  }
}

/**
 * Provides CodeLens annotations on pinned dependencies (`=`, `^`, `~`) that
 * lack an explanatory note, prompting the user to add one.
 */
class PinnedDepCodeLensProvider implements vscode.CodeLensProvider {
  provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
    const lines: string[] = [];
    for (let i = 0; i < document.lineCount; i++) {
      lines.push(document.lineAt(i).text);
    }

    return parsePinnedWithoutNote(lines).map((data) => {
      const range = new vscode.Range(data.line, 0, data.line, 0);
      const title =
        data.reason === "intransitive"
          ? '$(info) Intransitive without note — consider adding note = "..."'
          : '$(info) Pinned without note — consider adding { dependency = "...", note = "..." }';
      return new vscode.CodeLens(range, {
        title,
        command: "sbt-dependencies.addDependencyNote",
        arguments: [data.line],
      });
    });
  }
}

/** Decoration type that hides text by making it invisible and zero-width. */
const hideDecorationType = vscode.window.createTextEditorDecorationType({
  opacity: "0",
  letterSpacing: "-100em",
});

/** Decoration type used as a container for per-line `after` note text. */
const noteDecorationType = vscode.window.createTextEditorDecorationType({});

/**
 * Applies note decorations to a text editor, visually collapsing single-line
 * `{ dependency = "...", note = "..." }` entries into `"..." // note`.
 *
 * Lines where the cursor is positioned are excluded so the user can see
 * and edit the real content.
 */
function applyNoteDecorations(editor: vscode.TextEditor): void {
  if (editor.document.languageId !== "sbt-dependencies") return;

  const lines: string[] = [];
  for (let i = 0; i < editor.document.lineCount; i++) {
    lines.push(editor.document.lineAt(i).text);
  }

  const cursorLines = new Set(editor.selections.map(s => s.active.line));
  const decorations = parseNoteDecorations(lines).filter(d => !cursorLines.has(d.line));

  const hideRanges: vscode.DecorationOptions[] = [];
  const noteRanges: vscode.DecorationOptions[] = [];

  for (const d of decorations) {
    // Hide the prefix: `{ dependency = "`
    hideRanges.push({
      range: new vscode.Range(d.line, d.prefixRange.startCol, d.line, d.prefixRange.endCol),
    });

    // Hide the suffix: `", note = "..." }`
    hideRanges.push({
      range: new vscode.Range(d.line, d.suffixRange.startCol, d.line, d.suffixRange.endCol),
    });

    // Show note text as an after-decoration on the dependency string
    noteRanges.push({
      range: new vscode.Range(d.line, d.prefixRange.endCol, d.line, d.suffixRange.startCol),
      renderOptions: {
        after: {
          contentText: `  // ${d.noteText}`,
          color: new vscode.ThemeColor("editorLineNumber.foreground"),
          fontStyle: "italic",
        },
      },
    });
  }

  editor.setDecorations(hideDecorationType, hideRanges);
  editor.setDecorations(noteDecorationType, noteRanges);
}

/** Registers providers, commands, and diagnostics. */
export function activate(context: vscode.ExtensionContext): void {
  const selector: vscode.DocumentSelector = { language: "sbt-dependencies", scheme: "file" };

  context.subscriptions.push(
    vscode.languages.registerHoverProvider(
      selector,
      new DependencyHoverProvider()
    ),
    vscode.languages.registerDocumentSymbolProvider(
      selector,
      new DependencyDocumentSymbolProvider()
    ),
    vscode.languages.registerReferenceProvider(
      selector,
      new DependencyReferenceProvider()
    ),
    vscode.languages.registerDocumentLinkProvider(
      selector,
      new DependencyDocumentLinkProvider()
    ),
    vscode.languages.registerRenameProvider(
      selector,
      new DependencyRenameProvider()
    ),
    vscode.languages.registerDocumentFormattingEditProvider(
      selector,
      new DependencyDocumentFormattingProvider()
    ),
    vscode.languages.registerCodeActionsProvider(
      selector,
      new DependencyCodeActionProvider(),
      { providedCodeActionKinds: [vscode.CodeActionKind.QuickFix, vscode.CodeActionKind.RefactorRewrite] }
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
    ),
    vscode.languages.registerCodeLensProvider(
      { pattern: "**/*.sbt", scheme: "file" },
      new SbtBuildCodeLensProvider()
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.openDependenciesGroup",
      openDependenciesGroup
    ),
    vscode.languages.registerCodeLensProvider(
      selector,
      new DependencyGroupCodeLensProvider()
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.openBuildProject",
      openBuildProject
    ),
    vscode.languages.registerCodeLensProvider(
      selector,
      new PinnedDepCodeLensProvider()
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.addDependencyNote",
      addDependencyNote
    ),
  );

  const diagnostics = vscode.languages.createDiagnosticCollection("sbt-dependencies");
  context.subscriptions.push(diagnostics);

  context.subscriptions.push(
    vscode.workspace.onDidOpenTextDocument(doc => {
      updateDiagnostics(doc, diagnostics);
      warmAvailabilityCache(doc);
      warmRepoUrlCache(doc);
    }),
    vscode.workspace.onDidChangeTextDocument(e => {
      updateDiagnostics(e.document, diagnostics);
      warmAvailabilityCache(e.document);
      warmRepoUrlCache(e.document);
      const editor = vscode.window.activeTextEditor;
      if (editor && editor.document === e.document) {
        applyNoteDecorations(editor);
      }
    }),
    vscode.workspace.onDidCloseTextDocument(doc => diagnostics.delete(doc.uri)),
    vscode.window.onDidChangeTextEditorSelection(e => {
      applyNoteDecorations(e.textEditor);
    }),
    vscode.window.onDidChangeActiveTextEditor(editor => {
      if (editor) applyNoteDecorations(editor);
    })
  );

  context.subscriptions.push(hideDecorationType, noteDecorationType);

  vscode.workspace.textDocuments.forEach(doc => {
    updateDiagnostics(doc, diagnostics);
    warmAvailabilityCache(doc);
    warmRepoUrlCache(doc);
  });

  if (vscode.window.activeTextEditor) {
    applyNoteDecorations(vscode.window.activeTextEditor);
  }
}

export function deactivate(): void {}
