export interface QuickFixDescriptor {
  title: string;
  deleteLineIndex: number;
}

const fixableDiagnostics: Record<string, string> = {
  "Duplicate dependency in group": "Remove duplicate dependency",
  "Empty dependency string": "Remove empty dependency",
};

/**
 * Returns quick-fix descriptors for a diagnostic message.
 *
 * If the message matches a fixable diagnostic, returns an array with one
 * descriptor; otherwise returns an empty array.
 */
export function getQuickFixes(diagnosticMessage: string, lineIndex: number): QuickFixDescriptor[] {
  const title = fixableDiagnostics[diagnosticMessage];
  if (!title) return [];
  return [{ title, deleteLineIndex: lineIndex }];
}
