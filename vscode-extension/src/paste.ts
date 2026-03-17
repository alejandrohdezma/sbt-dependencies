import * as vscode from "vscode";
import { convertSbtDependency } from "./formatting";

/**
 * Intercepts paste events in `dependencies.conf` files and converts
 * SBT-style dependency strings (e.g. from mvnrepository.com) into the
 * canonical HOCON format used by sbt-dependencies.
 *
 * Comment lines (starting with `//` or `#`) and blank lines are stripped.
 * If no SBT dependency is found in the pasted text, the paste proceeds
 * unchanged.
 */
export class DependencyPasteEditProvider implements vscode.DocumentPasteEditProvider {

  static readonly kind = vscode.DocumentDropOrPasteEditKind.Empty.append("text", "sbt-dependencies");

  async provideDocumentPasteEdits(
    _document: vscode.TextDocument,
    _ranges: readonly vscode.Range[],
    dataTransfer: vscode.DataTransfer,
    _context: vscode.DocumentPasteEditContext,
    token: vscode.CancellationToken
  ): Promise<vscode.DocumentPasteEdit[] | undefined> {
    const item = dataTransfer.get("text/plain");
    if (!item) return undefined;

    const text = await item.asString();
    if (token.isCancellationRequested) return undefined;

    const lines = text.split(/\r?\n/);

    const converted: string[] = [];
    let hasMatch = false;

    for (const line of lines) {
      const trimmed = line.trim();

      // Skip blank lines and comments
      if (trimmed === "" || trimmed.startsWith("//") || trimmed.startsWith("#")) {
        continue;
      }

      const dep = convertSbtDependency(line);
      if (dep) {
        converted.push(`"${dep}"`);
        hasMatch = true;
      }
    }

    if (!hasMatch) return undefined;

    const snippet = new vscode.SnippetString(converted.join("\n"));
    return [
      new vscode.DocumentPasteEdit(snippet, "Paste as sbt-dependencies format", DependencyPasteEditProvider.kind),
    ];
  }
}
