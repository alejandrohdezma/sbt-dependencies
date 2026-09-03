import * as fs from "node:fs";
import * as crypto from "node:crypto";
import * as vscode from "vscode";
import {
  shouldPromptImport,
  importStatusBarText,
  importStatusBarTooltip,
  importPromptMessage,
  importPromptButton,
} from "./build-import";
import { parseCodeLenses } from "./codelens";
import { parseResolvedDecorations } from "./resolved-decorations";
import { dumpPathsFor, parseResolutionsDump, ResolutionsIndex, ResolutionLookup } from "./resolutions";
import { parsePinnedWithoutNote, parseBomManagedVersions } from "./dep-codelens";
import { parseDiagnostics } from "./diagnostics";
import { formatDocument } from "./formatting";
import { COMMON_SETTINGS, SBT_BUILD } from "./groups";
import { parseDependency, buildHoverMarkdown, normalizeVersions, HoverResolution } from "./hover";
import { parseGroupHeader, buildGroupHoverMarkdown } from "./group-hover";
import { parseDocumentLinks } from "./links";
import { parseNoteDecorations } from "./note-decorations";
import { DependencyPasteEditProvider } from "./paste";
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

/** Cache of parsed resolutions dumps, keyed by conf path and invalidated when either dump's mtime changes. */
const resolutionsCache = new Map<string, { index: ResolutionsIndex; mainMtime: number; metaMtime: number }>();

/** The file's modification time in millis, or `0` when it doesn't exist. */
function mtimeOf(filePath: string): number {
  try {
    return fs.statSync(filePath).mtimeMs;
  } catch {
    return 0;
  }
}

/** Reads and parses a dump file, returning `undefined` when absent or malformed. */
function readDump(filePath: string) {
  try {
    return parseResolutionsDump(fs.readFileSync(filePath, "utf8"));
  } catch {
    return undefined;
  }
}

/** Last buffer text per conf whose hash matched the dump — the baseline version-only edits are compared against. */
const freshBaselines = new Map<string, string>();

/**
 * The parsed dumps for a `dependencies.conf` document, or `undefined` when neither exists (feature off). Cached and
 * only re-read when a dump's mtime changes.
 */
function resolutionsIndexFor(document: vscode.TextDocument): ResolutionsIndex | undefined {
  if (document.languageId !== "sbt-dependencies") return undefined;

  const conf = document.uri.fsPath;
  const { main, meta } = dumpPathsFor(conf);
  const mainMtime = mtimeOf(main);
  const metaMtime = mtimeOf(meta);

  if (mainMtime === 0 && metaMtime === 0) {
    resolutionsCache.delete(conf);
    return undefined;
  }

  let entry = resolutionsCache.get(conf);
  if (!entry || entry.mainMtime !== mainMtime || entry.metaMtime !== metaMtime) {
    entry = {
      index: new ResolutionsIndex(mainMtime ? readDump(main) : undefined, metaMtime ? readDump(meta) : undefined),
      mainMtime,
      metaMtime,
    };
    resolutionsCache.set(conf, entry);
  }

  return entry.index.hasData ? entry.index : undefined;
}

/** SHA-1 of a buffer, in the form the dump records it. */
function hashOf(text: string): string {
  return crypto.createHash("sha1").update(text, "utf8").digest("hex");
}

/**
 * Whether the sbt build needs re-importing: the buffer no longer hashes to what the plugin recorded when it wrote the
 * dump. Unlike `ResolutionLookup.stale` this counts version-only edits, which leave the dump's pins usable but still
 * change the classpath.
 */
function needsBuildImport(document: vscode.TextDocument): boolean {
  const index = resolutionsIndexFor(document);

  return index?.sourceHash !== undefined && index.sourceHash !== hashOf(document.getText());
}

/**
 * Returns a resolution lookup for a `dependencies.conf` document, or `undefined` when no dump exists (feature off).
 *
 * Staleness is an exact SHA-1 mismatch between the current buffer and the hash the plugin recorded when it wrote the
 * dump — except when the buffer differs from the last matching text only in version tokens (a rewrite to `*`, a manual
 * bump): pins are keyed by group/org/artifact, so the dump stays usable until something other than a version changes.
 * Use `needsBuildImport` instead to tell whether the build itself is out of date.
 */
function getResolutions(document: vscode.TextDocument): ResolutionLookup | undefined {
  const index = resolutionsIndexFor(document);
  if (!index) return undefined;

  const conf = document.uri.fsPath;
  let stale = index.sourceHash !== undefined && index.sourceHash !== hashOf(document.getText());

  if (!stale) {
    freshBaselines.set(conf, document.getText());
  } else {
    const baseline = freshBaselines.get(conf);
    if (baseline !== undefined && normalizeVersions(baseline) === normalizeVersions(document.getText())) stale = false;
  }

  return index.asLookup(stale);
}

/** The name of the group whose range contains `line`, or `undefined` when outside any group. */
function groupAtLine(lines: string[], line: number): string | undefined {
  return parseDocumentSymbols(lines).find(
    (s) => s.kind === "group" && line >= s.range.startLine && line <= s.range.endLine
  )?.name;
}

/**
 * Resolves the hover provenance for a `*` or `{{variable}}` dependency at `line`, or `undefined` for any other version
 * or when no dump resolves it.
 */
function resolveHoverVersion(
  document: vscode.TextDocument,
  line: number,
  org: string,
  artifact: string,
  version: string | undefined,
  isCross: boolean
): HoverResolution | undefined {
  if (version !== "*" && !version?.startsWith("{{")) return undefined;

  const lookup = getResolutions(document);
  if (!lookup) return undefined;

  const lines: string[] = [];
  for (let i = 0; i < document.lineCount; i++) lines.push(document.lineAt(i).text);

  const group = groupAtLine(lines, line);
  if (!group) return undefined;

  if (version === "*") {
    const w = lookup.resolveWildcard(group, org, artifact, isCross);
    return w && {
      version: w.version,
      stale: lookup.stale,
      source: { kind: "bom", organization: w.bom.organization, name: w.bom.name, bomVersion: w.bom.version },
    };
  }

  const v = lookup.resolveVariable(group, org, artifact, isCross);
  return v && { version: v.version, stale: lookup.stale, source: { kind: "variable", variable: v.variable } };
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

    const resolution = resolveHoverVersion(document, position.line, dep.org, dep.artifact, dep.version, isScala);

    const md = new vscode.MarkdownString();
    md.isTrusted = true;
    md.appendMarkdown(buildHoverMarkdown(dep, available, resolution));

    const matchRange = new vscode.Range(
      position.line, dep.matchStart,
      position.line, dep.matchEnd
    );

    return new vscode.Hover(md, matchRange);
  }
}

/**
 * Provides hover tooltips for the reserved group headers
 * (`common-settings` and `sbt-build`), explaining what each group
 * means and which fields it accepts. Project groups fall through with
 * no hover.
 */
class GroupHeaderHoverProvider implements vscode.HoverProvider {
  provideHover(
    document: vscode.TextDocument,
    position: vscode.Position
  ): vscode.Hover | undefined {
    const text = document.lineAt(position.line).text;
    const header = parseGroupHeader(text);

    if (!header) return undefined;
    if (position.character < header.startCol || position.character > header.endCol) return undefined;

    const markdown = buildGroupHoverMarkdown(header.name);
    if (!markdown) return undefined;

    const md = new vscode.MarkdownString(markdown);
    md.isTrusted = true;

    const range = new vscode.Range(
      position.line, header.startCol,
      position.line, header.endCol
    );

    return new vscode.Hover(md, range);
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
class DependencyDocumentFormattingProvider implements vscode.DocumentFormattingEditProvider, vscode.DocumentRangeFormattingEditProvider {
  provideDocumentFormattingEdits(
    document: vscode.TextDocument
  ): vscode.TextEdit[] {
    return this.formatFullDocument(document);
  }

  provideDocumentRangeFormattingEdits(
    document: vscode.TextDocument
  ): vscode.TextEdit[] {
    return this.formatFullDocument(document);
  }

  private formatFullDocument(document: vscode.TextDocument): vscode.TextEdit[] {
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
 * The `sbt-build` and `common-settings` groups use separate global commands.
 */
function getInstallCommand(groupName: string, dependency: string): string {
  if (groupName === SBT_BUILD) {
    return `sbtn installBuildDependencies ${dependency}`;
  }
  if (groupName === COMMON_SETTINGS) {
    return `sbtn installCommonDependencies ${dependency}`;
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
 * Code Action handler: prompts the user for a variable name, then replaces
 * all numeric dependency versions in the given group with `{{variableName}}`.
 */
async function replaceVersionsWithVariable(groupName: string): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  if (!editor || editor.document.languageId !== "sbt-dependencies") {
    vscode.window.showErrorMessage("Open a dependencies.conf file first.");
    return;
  }

  const document = editor.document;
  const lines: string[] = [];
  for (let i = 0; i < document.lineCount; i++) {
    lines.push(document.lineAt(i).text);
  }

  const symbols = parseDocumentSymbols(lines);
  const group = symbols.find((s) => s.name === groupName);
  if (!group || !group.children) {
    vscode.window.showWarningMessage(`Group '${groupName}' not found.`);
    return;
  }

  // Collect version positions for dependencies with numeric versions
  const replacements: { line: number; startCol: number; endCol: number }[] = [];
  for (const child of group.children) {
    if (child.kind !== "dependency") continue;
    const line = lines[child.range.startLine];
    const dep = parseDependency(line);
    if (!dep || !dep.version || dep.version.startsWith("{{")) continue;
    const versionStart = dep.matchStart + dep.org.length + dep.separator.length + dep.artifact.length + 1;
    replacements.push({ line: child.range.startLine, startCol: versionStart, endCol: versionStart + dep.version.length });
  }

  if (replacements.length === 0) {
    vscode.window.showInformationMessage(`No numeric versions found in '${groupName}'.`);
    return;
  }

  const variableName = await vscode.window.showInputBox({
    prompt: `Variable name for versions in '${groupName}'`,
    placeHolder: "e.g., circeVersion",
    validateInput: (value) => {
      if (!value || !/^\w+$/.test(value)) {
        return "Variable name must contain only letters, digits, and underscores";
      }
      return undefined;
    },
  });
  if (!variableName) return;

  const wsEdit = new vscode.WorkspaceEdit();
  for (const r of replacements) {
    wsEdit.replace(
      document.uri,
      new vscode.Range(r.line, r.startCol, r.line, r.endCol),
      `{{${variableName}}}`
    );
  }
  await vscode.workspace.applyEdit(wsEdit);
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

      // Materialize a `*` into the resolved concrete version (the forward `version -> *` suggestion is a CodeLens).
      if (dep.version === "*") {
        const confLines: string[] = [];
        for (let i = 0; i < document.lineCount; i++) confLines.push(document.lineAt(i).text);
        const group = groupAtLine(confLines, range.start.line);
        const lookup = group ? getResolutions(document) : undefined;
        const resolved = lookup && !lookup.stale ? lookup.resolveWildcard(group!, dep.org, dep.artifact, dep.separator === "::") : undefined;

        if (resolved) {
          const versionStart = dep.matchStart + dep.org.length + dep.separator.length + dep.artifact.length + 1;
          const rewrite = new vscode.CodeAction(`Replace * with resolved version ${resolved.version}`, vscode.CodeActionKind.RefactorRewrite);
          const edit = new vscode.WorkspaceEdit();
          edit.replace(document.uri, new vscode.Range(range.start.line, versionStart, range.start.line, versionStart + 1), resolved.version);
          rewrite.edit = edit;
          actions.push(rewrite);
        }
      }
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

      const replaceAction = new vscode.CodeAction(
        `Replace versions with variable in '${groupName}'`,
        vscode.CodeActionKind.RefactorRewrite
      );
      replaceAction.command = {
        command: "sbt-dependencies.replaceVersionsWithVariable",
        title: `Replace versions with variable in '${groupName}'`,
        arguments: [groupName],
      };
      actions.push(replaceAction);
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
 * Command handler for the BOM-managed CodeLens: replaces the hardcoded version on `line` with `*`, so the version is
 * taken from the BOM.
 */
async function useBomManagedVersion(line: number): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  if (!editor) return;

  const dep = parseDependency(editor.document.lineAt(line).text);
  if (!dep?.version || dep.version === "*") return;

  const versionStart = dep.matchStart + dep.org.length + dep.separator.length + dep.artifact.length + 1;
  const range = new vscode.Range(line, versionStart, line, versionStart + dep.version.length);

  await editor.edit((editBuilder) => editBuilder.replace(range, "*"));
}

/**
 * Command handler for the "replace all" BOM-managed CodeLens: replaces every version a visible BOM pins with `*` in a
 * single edit, reusing the same scan the lenses come from.
 */
async function useBomManagedVersions(): Promise<void> {
  const editor = vscode.window.activeTextEditor;
  if (!editor || editor.document.languageId !== "sbt-dependencies") return;

  const lookup = getResolutions(editor.document);
  if (!lookup || lookup.stale) {
    vscode.window.showWarningMessage(
      "BOM resolutions are missing or stale — import the sbt build first so the resolutions dump is up to date."
    );
    return;
  }

  const lines: string[] = [];
  for (let i = 0; i < editor.document.lineCount; i++) {
    lines.push(editor.document.lineAt(i).text);
  }

  const rewrites = parseBomManagedVersions(lines, lookup);
  if (rewrites.length === 0) {
    vscode.window.showInformationMessage("No BOM-managed versions to replace.");
    return;
  }

  await editor.edit((editBuilder) => {
    for (const rewrite of rewrites) {
      const dep = parseDependency(editor.document.lineAt(rewrite.line).text);
      if (!dep?.version || dep.version === "*") continue;

      const versionStart = dep.matchStart + dep.org.length + dep.separator.length + dep.artifact.length + 1;
      editBuilder.replace(new vscode.Range(rewrite.line, versionStart, rewrite.line, versionStart + dep.version.length), "*");
    }
  });

  vscode.window.showInformationMessage(`Replaced ${rewrites.length} BOM-managed version(s) with *.`);
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
          ? '$(info) Intransitive without note — consider adding note = "..."'
          : '$(info) Pinned without note — consider adding { dependency = "...", note = "..." }';
      return new vscode.CodeLens(range, {
        title,
        command: "sbt-dependencies.addDependencyNote",
        arguments: [data.line],
      });
    });
  }
}

/**
 * Provides a CodeLens on hardcoded dependency versions that a visible BOM manages, suggesting they be replaced with
 * `*`. Refreshes when the resolutions dump changes (via {@link BomManagedCodeLensProvider.refresh}); shows nothing
 * while the dump is stale, to avoid suggesting rewrites from out-of-date data.
 */
class BomManagedCodeLensProvider implements vscode.CodeLensProvider {
  private readonly emitter = new vscode.EventEmitter<void>();
  readonly onDidChangeCodeLenses = this.emitter.event;

  /** Signals VS Code to re-query the lenses (called when the dump changes). */
  refresh(): void {
    this.emitter.fire();
  }

  provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] {
    const lookup = getResolutions(document);
    if (!lookup || lookup.stale) return [];

    const lines: string[] = [];
    for (let i = 0; i < document.lineCount; i++) {
      lines.push(document.lineAt(i).text);
    }

    const managed = parseBomManagedVersions(lines, lookup);

    return managed.flatMap((data) => {
      const range = new vscode.Range(data.line, 0, data.line, 0);
      const lenses = [
        new vscode.CodeLens(range, {
          title: `$(sparkle) Managed by ${data.bomName} — replace ${data.version} with *`,
          command: "sbt-dependencies.useBomManagedVersion",
          arguments: [data.line],
        }),
      ];
      if (managed.length > 1) {
        lenses.push(
          new vscode.CodeLens(range, {
            title: `$(sparkle) Replace all ${managed.length} BOM-managed versions with *`,
            command: "sbt-dependencies.useBomManagedVersions",
          })
        );
      }
      return lenses;
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

/** Decoration type used as a container for per-line `after` resolved-version text. */
const resolvedDecorationType = vscode.window.createTextEditorDecorationType({});

/**
 * Applies resolved-version decorations to a text editor, rendering the concrete version of `*` and `{{variable}}`
 * dependencies (from the plugin's resolutions dump) as ghost text after the dependency string.
 *
 * A no-op that clears any existing decorations when no dump is available for the document.
 */
function applyResolvedDecorations(editor: vscode.TextEditor): void {
  if (editor.document.languageId !== "sbt-dependencies") return;

  const lookup = getResolutions(editor.document);
  if (!lookup) {
    editor.setDecorations(resolvedDecorationType, []);
    return;
  }

  const lines: string[] = [];
  for (let i = 0; i < editor.document.lineCount; i++) {
    lines.push(editor.document.lineAt(i).text);
  }

  const decorations: vscode.DecorationOptions[] = parseResolvedDecorations(lines, lookup).map((d) => ({
    range: new vscode.Range(d.line, d.afterCol, d.line, d.afterCol),
    renderOptions: {
      after: {
        contentText: d.text,
        color: new vscode.ThemeColor("editorLineNumber.foreground"),
        fontStyle: "italic",
      },
    },
  }));

  editor.setDecorations(resolvedDecorationType, decorations);
}

/** Registers providers, commands, and diagnostics. */
/**
 * Imports the sbt build through Metals, the same action its own "build needs to be re-imported"
 * notification runs. The `metals.build-import` command only exists once the Metals language client
 * has started, hence the fallback message.
 */
async function runImportBuild(): Promise<void> {
  if (!vscode.extensions.getExtension("scalameta.metals")) {
    vscode.window.showErrorMessage("The Metals extension is required to import the sbt build.");
    return;
  }

  try {
    await vscode.commands.executeCommand("metals.build-import");
  } catch {
    vscode.window.showErrorMessage("Metals is not ready yet. Try again once it has started.");
  }
}

export function activate(context: vscode.ExtensionContext): void {
  const selector: vscode.DocumentSelector = { language: "sbt-dependencies", scheme: "file" };

  const bomManagedCodeLensProvider = new BomManagedCodeLensProvider();

  context.subscriptions.push(
    vscode.languages.registerHoverProvider(
      selector,
      new DependencyHoverProvider()
    ),
    vscode.languages.registerHoverProvider(
      selector,
      new GroupHeaderHoverProvider()
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
    vscode.languages.registerDocumentRangeFormattingEditProvider(
      selector,
      new DependencyDocumentFormattingProvider()
    ),
    vscode.languages.registerDocumentPasteEditProvider(
      selector,
      new DependencyPasteEditProvider(),
      {
        providedPasteEditKinds: [DependencyPasteEditProvider.kind],
        pasteMimeTypes: ["text/plain"],
      }
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
    vscode.commands.registerCommand(
      "sbt-dependencies.replaceVersionsWithVariable",
      replaceVersionsWithVariable
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
    vscode.languages.registerCodeLensProvider(
      selector,
      bomManagedCodeLensProvider
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.addDependencyNote",
      addDependencyNote
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.useBomManagedVersion",
      useBomManagedVersion
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.useBomManagedVersions",
      useBomManagedVersions
    ),
    vscode.commands.registerCommand(
      "sbt-dependencies.importBuild",
      runImportBuild
    ),
  );

  const importStatusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left);
  importStatusBarItem.text = importStatusBarText;
  importStatusBarItem.tooltip = importStatusBarTooltip;
  importStatusBarItem.command = "sbt-dependencies.importBuild";
  context.subscriptions.push(importStatusBarItem);

  const updateImportStatus = (editor: vscode.TextEditor | undefined) => {
    if (editor && needsBuildImport(editor.document)) {
      importStatusBarItem.show();
    } else {
      importStatusBarItem.hide();
    }
  };

  const promptedHashes = new Map<string, string>();

  context.subscriptions.push(
    vscode.workspace.onDidSaveTextDocument(async document => {
      if (document.languageId !== "sbt-dependencies") return;
      if (!vscode.workspace.getConfiguration("sbt-dependencies").get("buildImportPrompt", true)) return;

      const stale = needsBuildImport(document);
      const hash = hashOf(document.getText());
      const metalsInstalled = vscode.extensions.getExtension("scalameta.metals") !== undefined;

      if (!shouldPromptImport(stale, metalsInstalled, promptedHashes.get(document.uri.fsPath), hash)) return;

      promptedHashes.set(document.uri.fsPath, hash);

      const choice = await vscode.window.showInformationMessage(importPromptMessage, importPromptButton);
      if (choice === importPromptButton) await runImportBuild();
    })
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
        applyResolvedDecorations(editor);
        updateImportStatus(editor);
      }
    }),
    vscode.workspace.onDidCloseTextDocument(doc => diagnostics.delete(doc.uri)),
    vscode.window.onDidChangeTextEditorSelection(e => {
      applyNoteDecorations(e.textEditor);
    }),
    vscode.window.onDidChangeActiveTextEditor(editor => {
      if (editor) {
        applyNoteDecorations(editor);
        applyResolvedDecorations(editor);
      }
      updateImportStatus(editor);
    })
  );

  // Refresh resolved-version decorations when the plugin rewrites its dump on sbt (re)load.
  const refreshResolutions = () => {
    resolutionsCache.clear();
    for (const editor of vscode.window.visibleTextEditors) {
      applyResolvedDecorations(editor);
    }
    bomManagedCodeLensProvider.refresh();
    updateImportStatus(vscode.window.activeTextEditor);
  };

  const resolutionsWatcher = vscode.workspace.createFileSystemWatcher("**/target/sbt-dependencies/.sbt-resolutions");
  resolutionsWatcher.onDidCreate(refreshResolutions);
  resolutionsWatcher.onDidChange(refreshResolutions);
  resolutionsWatcher.onDidDelete(refreshResolutions);

  // VS Code's watcher is often suppressed for `target/` (e.g. Metals users keep `**/target` in
  // `files.watcherExclude`), so also poll the dump paths with node's `fs.watchFile`, which ignores that setting.
  const watchedDumps = new Set<string>();
  const watchDumps = (document: vscode.TextDocument) => {
    if (document.languageId !== "sbt-dependencies") return;
    const { main, meta } = dumpPathsFor(document.uri.fsPath);
    for (const dump of [main, meta]) {
      if (watchedDumps.has(dump)) continue;
      watchedDumps.add(dump);
      fs.watchFile(dump, { interval: 1000 }, () => refreshResolutions());
    }
  };

  context.subscriptions.push(hideDecorationType, noteDecorationType, resolvedDecorationType, resolutionsWatcher, {
    dispose: () => {
      watchedDumps.forEach(dump => fs.unwatchFile(dump));
      watchedDumps.clear();
    },
  });

  context.subscriptions.push(
    vscode.workspace.onDidOpenTextDocument(watchDumps),
    vscode.window.onDidChangeActiveTextEditor(editor => {
      if (editor) watchDumps(editor.document);
    })
  );

  vscode.workspace.textDocuments.forEach(doc => {
    updateDiagnostics(doc, diagnostics);
    warmAvailabilityCache(doc);
    warmRepoUrlCache(doc);
    watchDumps(doc);
  });

  if (vscode.window.activeTextEditor) {
    applyNoteDecorations(vscode.window.activeTextEditor);
    applyResolvedDecorations(vscode.window.activeTextEditor);
  }

  updateImportStatus(vscode.window.activeTextEditor);
}

export function deactivate(): void {}
