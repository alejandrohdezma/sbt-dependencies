/**
 * Reserved group names in `dependencies.conf` and the canonical ordering
 * used when writing the file. Mirrored from the Scala `Groups` object so
 * the extension and the SBT plugin stay aligned.
 */

/** Group whose dependencies become the meta-build (`project/project/plugins.sbt`). */
export const SBT_BUILD = "sbt-build";

/** Group whose `scala-version[s]` / `java-version` / `dependencies` apply to every non-meta project as defaults. */
export const COMMON_SETTINGS = "common-settings";

/** Group names that cannot be used as SBT project names. */
export const RESERVED: ReadonlySet<string> = new Set([SBT_BUILD, COMMON_SETTINGS]);

/**
 * Sort key for group names. Sorts `sbt-build` first, then `common-settings`,
 * then the remaining groups alphabetically. Composes with any string comparator.
 *
 * Each tier gets a leading-space prefix so that `common-settings` always sorts
 * second regardless of how a sibling project name compares alphabetically —
 * space (ASCII 32) is less than any letter, so two-space and one-space
 * prefixes sort before unprefixed names.
 */
export function groupSortKey(name: string): string {
  if (name === SBT_BUILD) return "  " + name;
  if (name === COMMON_SETTINGS) return " " + name;
  return name;
}
