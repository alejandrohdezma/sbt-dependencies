import { simpleGroupStartPattern, advancedGroupStartPattern } from "./parser";
import { COMMON_SETTINGS, SBT_BUILD } from "./groups";

export interface GroupHeaderMatch {
  name: string;
  /** Column of the first character of the group name. */
  startCol: number;
  /** Column just after the last character of the group name. */
  endCol: number;
}

/**
 * Parses a group-header line (`name = [...]` or `name {`) and returns the
 * column range covering just the group name. Returns `undefined` when the
 * line is not a group-header line.
 *
 * The returned range deliberately covers only the name so that hovering
 * over a dependency string on the same line (single-line simple groups)
 * still falls through to the dependency hover provider.
 */
export function parseGroupHeader(line: string): GroupHeaderMatch | undefined {
  const match = simpleGroupStartPattern.exec(line) ?? advancedGroupStartPattern.exec(line);
  if (!match) return undefined;

  const indent = match[1] ?? "";
  const name = match[2];
  const startCol = indent.length;
  return { name, startCol, endCol: startCol + name.length };
}

const fence = "```";

const commonSettingsMd = `**common-settings** — build-wide defaults

Declares dependencies, Scala versions, and Java target that apply to every
non-meta project. A per-project group overrides by \`(organization, name)\`
for deps, or wholesale for \`scala-version[s]\` and \`java-version\`.

${fence}hocon
common-settings {
  scala-versions = ["2.13.16", "3.3.7"]
  java-version   = "17"
  dependencies   = [
    "org.typelevel::cats-core:2.10.0"
  ]
}
${fence}

Use \`installCommonDependencies\` / \`updateCommonDependencies\` from sbt to
manage entries.

[Learn more](https://github.com/alejandrohdezma/sbt-dependencies#readme)`;

const sbtBuildMd = `**sbt-build** — meta-build dependencies

Declares dependencies for the build definition itself: sbt plugins
(\`:sbt-plugin\`) and libraries used in \`build.sbt\`. Cannot define
\`scala-version[s]\` or \`java-version\` — those belong in \`common-settings\`
(build-wide) or in per-project groups.

${fence}hocon
sbt-build = [
  "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"
  "org.scalameta:sbt-scalafmt:2.5.4:sbt-plugin"
]
${fence}

Use \`installBuildDependencies\` / \`updateBuildDependencies\` from sbt to
manage entries.

[Learn more](https://github.com/alejandrohdezma/sbt-dependencies#readme)`;

/**
 * Returns the hover markdown for a reserved group name (`common-settings`
 * or `sbt-build`), or `undefined` for any other group name (project
 * groups have no canned explanation).
 */
export function buildGroupHoverMarkdown(name: string): string | undefined {
  if (name === COMMON_SETTINGS) return commonSettingsMd;
  if (name === SBT_BUILD) return sbtBuildMd;
  return undefined;
}
