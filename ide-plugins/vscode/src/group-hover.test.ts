import { describe, it, expect } from "vitest";
import { parseGroupHeader, buildGroupHoverMarkdown } from "./group-hover";

describe("parseGroupHeader", () => {
  it("parses a simple group header", () => {
    const result = parseGroupHeader("common-settings = [");
    expect(result).toEqual({ name: "common-settings", startCol: 0, endCol: 15 });
  });

  it("parses an advanced group header", () => {
    const result = parseGroupHeader("common-settings {");
    expect(result).toEqual({ name: "common-settings", startCol: 0, endCol: 15 });
  });

  it("parses an sbt-build header", () => {
    const result = parseGroupHeader("sbt-build = [");
    expect(result).toEqual({ name: "sbt-build", startCol: 0, endCol: 9 });
  });

  it("respects leading indentation", () => {
    const result = parseGroupHeader("   sbt-build = [");
    expect(result).toEqual({ name: "sbt-build", startCol: 3, endCol: 12 });
  });

  it("parses a custom project group", () => {
    const result = parseGroupHeader("my-project = [");
    expect(result).toEqual({ name: "my-project", startCol: 0, endCol: 10 });
  });

  it("returns undefined for a dependency line", () => {
    expect(parseGroupHeader('  "org.typelevel::cats-core:2.10.0"')).toBeUndefined();
  });

  it("returns undefined for a comment line", () => {
    expect(parseGroupHeader("# common-settings = [")).toBeUndefined();
  });

  it("returns undefined for an empty line", () => {
    expect(parseGroupHeader("")).toBeUndefined();
  });

  it("returns undefined for a closing brace", () => {
    expect(parseGroupHeader("}")).toBeUndefined();
  });
});

describe("buildGroupHoverMarkdown", () => {
  it("returns markdown for common-settings", () => {
    const md = buildGroupHoverMarkdown("common-settings");
    expect(md).toBeDefined();
    expect(md).toContain("**common-settings**");
    expect(md).toContain("build-wide defaults");
    expect(md).toContain("installCommonDependencies");
    expect(md).toContain("updateCommonDependencies");
    expect(md).toContain("[Learn more]");
  });

  it("returns markdown for sbt-build", () => {
    const md = buildGroupHoverMarkdown("sbt-build");
    expect(md).toBeDefined();
    expect(md).toContain("**sbt-build**");
    expect(md).toContain("meta-build");
    expect(md).toContain("installBuildDependencies");
    expect(md).toContain("updateBuildDependencies");
  });

  it("returns undefined for a project group", () => {
    expect(buildGroupHoverMarkdown("my-project")).toBeUndefined();
  });

  it("returns undefined for an empty name", () => {
    expect(buildGroupHoverMarkdown("")).toBeUndefined();
  });

  it("returns undefined for a name that looks like a field", () => {
    expect(buildGroupHoverMarkdown("dependencies")).toBeUndefined();
  });
});
