export type QuickFixDescriptor =
  | { kind: "delete-line"; title: string; deleteLineIndex: number }
  | { kind: "replace-range"; title: string; newText: string };

const deletableDiagnostics: Record<string, string> = {
  "Duplicate dependency in group": "Remove duplicate dependency",
  "Empty dependency string": "Remove empty dependency",
};

/** Matches the hint emitted for a dependency a visible BOM pins, e.g. `jackson-databind is pinned by com.fasterxml.jackson:jackson-bom at 2.17.0`. */
const pinnedByBomPattern = /^\S+ is pinned by \S+ at \S+$/;

/**
 * Returns quick-fix descriptors for a diagnostic message.
 *
 * Deletable diagnostics map to a `delete-line` fix; the BOM-pinned hint maps to a `replace-range` fix that swaps the
 * version (the diagnostic's own range) for `*`. Unrecognized messages return an empty array.
 */
export function getQuickFixes(diagnosticMessage: string, lineIndex: number): QuickFixDescriptor[] {
  const deleteTitle = deletableDiagnostics[diagnosticMessage];
  if (deleteTitle) return [{ kind: "delete-line", title: deleteTitle, deleteLineIndex: lineIndex }];

  if (pinnedByBomPattern.test(diagnosticMessage)) {
    return [{ kind: "replace-range", title: 'Replace version with "*"', newText: "*" }];
  }

  return [];
}
