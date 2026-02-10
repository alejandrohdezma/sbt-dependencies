import { describe, it, expect } from "vitest";
import { parseDependency, buildHoverMarkdown, DependencyMatch } from "./hover";

describe("parseDependency", () => {
  it("parses a standard Scala dep", () => {
    const result = parseDependency('"org.typelevel::cats-core:2.10.0"');
    expect(result).toBeDefined();
    expect(result!.org).toBe("org.typelevel");
    expect(result!.separator).toBe("::");
    expect(result!.artifact).toBe("cats-core");
    expect(result!.version).toBe("2.10.0");
    expect(result!.config).toBeUndefined();
  });

  it("parses a Java dep", () => {
    const result = parseDependency('"com.typesafe:config:1.4.3"');
    expect(result).toBeDefined();
    expect(result!.org).toBe("com.typesafe");
    expect(result!.separator).toBe(":");
    expect(result!.artifact).toBe("config");
    expect(result!.version).toBe("1.4.3");
  });

  it("parses = version marker", () => {
    const result = parseDependency('"io.circe::circe-core:=0.14.6"');
    expect(result).toBeDefined();
    expect(result!.version).toBe("=0.14.6");
  });

  it("parses ^ version marker", () => {
    const result = parseDependency('"org.typelevel::cats-core:^2.10.0"');
    expect(result).toBeDefined();
    expect(result!.version).toBe("^2.10.0");
  });

  it("parses ~ version marker", () => {
    const result = parseDependency('"org.http4s::http4s-core:~0.23.25"');
    expect(result).toBeDefined();
    expect(result!.version).toBe("~0.23.25");
  });

  it("parses variable version", () => {
    const result = parseDependency('"com.disneystreaming.smithy4s::smithy4s-core:{{smithy4sVersion}}"');
    expect(result).toBeDefined();
    expect(result!.version).toBe("{{smithy4sVersion}}");
  });

  it("parses dep with test configuration", () => {
    const result = parseDependency('"org.scalameta::munit:1.0.0:test"');
    expect(result).toBeDefined();
    expect(result!.version).toBe("1.0.0");
    expect(result!.config).toBe("test");
  });

  it("parses dep with sbt-plugin configuration", () => {
    const result = parseDependency('"ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"');
    expect(result).toBeDefined();
    expect(result!.config).toBe("sbt-plugin");
  });

  it("parses dep without version", () => {
    const result = parseDependency('"org.typelevel::cats-core"');
    expect(result).toBeDefined();
    expect(result!.org).toBe("org.typelevel");
    expect(result!.separator).toBe("::");
    expect(result!.artifact).toBe("cats-core");
    expect(result!.version).toBeUndefined();
    expect(result!.config).toBeUndefined();
  });

  it("returns undefined for a comment line", () => {
    expect(parseDependency("# this is a comment")).toBeUndefined();
  });

  it("returns undefined for an empty line", () => {
    expect(parseDependency("")).toBeUndefined();
  });

  it("returns correct matchStart and matchEnd", () => {
    const result = parseDependency('  "org.typelevel::cats-core:2.10.0"');
    expect(result).toBeDefined();
    expect(result!.matchStart).toBe(3);
    expect(result!.matchEnd).toBe(3 + "org.typelevel::cats-core:2.10.0".length);
  });
});

describe("buildHoverMarkdown", () => {
  const baseDep: DependencyMatch = {
    org: "org.typelevel",
    separator: "::",
    artifact: "cats-core",
    version: "2.10.0",
    matchStart: 0,
    matchEnd: 30,
  };

  it("includes header with org, separator, and artifact", () => {
    const md = buildHoverMarkdown(baseDep, false);
    expect(md).toContain("**org.typelevel** `::` **cats-core**");
  });

  it("shows 'update to latest' for plain version", () => {
    const md = buildHoverMarkdown(baseDep, false);
    expect(md).toContain("Version: `2.10.0` *(update to latest)*");
  });

  it("shows 'pinned' for = marker", () => {
    const md = buildHoverMarkdown({ ...baseDep, version: "=0.14.6" }, false);
    expect(md).toContain("Version: `=0.14.6` *(pinned)*");
  });

  it("shows 'update within major' for ^ marker", () => {
    const md = buildHoverMarkdown({ ...baseDep, version: "^2.10.0" }, false);
    expect(md).toContain("Version: `^2.10.0` *(update within major)*");
  });

  it("shows 'update within minor' for ~ marker", () => {
    const md = buildHoverMarkdown({ ...baseDep, version: "~0.23.25" }, false);
    expect(md).toContain("Version: `~0.23.25` *(update within minor)*");
  });

  it("shows 'resolved from variable' for variable version", () => {
    const md = buildHoverMarkdown({ ...baseDep, version: "{{smithy4sVersion}}" }, false);
    expect(md).toContain("Version: `{{smithy4sVersion}}` *(resolved from variable)*");
  });

  it("shows 'resolved to latest' when no version", () => {
    const md = buildHoverMarkdown({ ...baseDep, version: undefined }, false);
    expect(md).toContain("Version: *resolved to latest*");
  });

  it("includes configuration line when present", () => {
    const md = buildHoverMarkdown({ ...baseDep, config: "test" }, false);
    expect(md).toContain("Configuration: `test`");
  });

  it("omits configuration line when absent", () => {
    const md = buildHoverMarkdown(baseDep, false);
    expect(md).not.toContain("Configuration:");
  });

  it("includes mvnrepository link when available is true", () => {
    const md = buildHoverMarkdown(baseDep, true);
    expect(md).toContain("[Open on mvnrepository](https://mvnrepository.com/artifact/org.typelevel/cats-core)");
  });

  it("omits mvnrepository link when available is false", () => {
    const md = buildHoverMarkdown(baseDep, false);
    expect(md).not.toContain("mvnrepository");
  });

  it("uses _2.12_1.0 suffix in URL for sbt-plugin config", () => {
    const dep: DependencyMatch = {
      org: "ch.epfl.scala",
      separator: ":",
      artifact: "sbt-scalafix",
      version: "0.14.5",
      config: "sbt-plugin",
      matchStart: 0,
      matchEnd: 40,
    };
    const md = buildHoverMarkdown(dep, true);
    expect(md).toContain("[Open on mvnrepository](https://mvnrepository.com/artifact/ch.epfl.scala/sbt-scalafix_2.12_1.0)");
  });
});
