/**
 * Decision logic for the "import sbt build" prompt shown when `dependencies.conf` changes after the last sbt
 * (re)load, mirroring the IntelliJ plugin's reload widget. Metals' own change detection only covers `.sbt`,
 * `.scala` and `build.properties` files, so the extension prompts itself and delegates the import to Metals.
 *
 * Pure module: the vscode wiring (status bar item, notification, command) lives in `extension.ts`.
 */

export const importStatusBarText = "$(warning) sbt build outdated";

export const importStatusBarTooltip =
  "dependencies.conf changed since the last sbt import. Click to import the build with Metals.";

export const importPromptMessage = "dependencies.conf changed since the last sbt import.";

export const importPromptButton = "Import build";

/**
 * Whether saving a document warrants the import notification: the buffer is stale against the last import, Metals
 * is installed to execute it, and this exact content hasn't been prompted for already (so the notification shows
 * once per change, not on every save).
 */
export function shouldPromptImport(
  stale: boolean | undefined,
  metalsInstalled: boolean,
  alreadyPromptedHash: string | undefined,
  currentHash: string
): boolean {
  return stale === true && metalsInstalled && alreadyPromptedHash !== currentHash;
}
