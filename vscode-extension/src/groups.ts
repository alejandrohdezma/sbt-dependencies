/**
 * Reserved group names in `dependencies.conf` and the canonical ordering
 * used when writing the file. Mirrored from the Scala `Groups` object so
 * the extension and the SBT plugin stay aligned.
 */

/** Group whose dependencies become the meta-build (`project/project/plugins.sbt`). */
export const SBT_BUILD = "sbt-build";

/** Group names that cannot be used as SBT project names. */
export const RESERVED: ReadonlySet<string> = new Set([SBT_BUILD]);

/**
 * Sort key for group names. Sorts `sbt-build` first, then the remaining groups alphabetically.
 * Composes with any string comparator.
 */
export function groupSortKey(name: string): string {
  if (name === SBT_BUILD) return "  " + name;
  return name;
}
