import { describe, it, expect } from "vitest";
import { parseResolvedDecorations } from "./resolved-decorations";
import { ResolutionLookup } from "./resolutions";

/** A fake lookup: resolves cats-core (Scala) and jackson-databind (Java) for the `myproject` group only. */
function fakeLookup(stale = false): ResolutionLookup {
  return {
    resolveWildcard: (group, org, name, isCross) => {
      if (group === "myproject" && org === "com.fasterxml.jackson.core" && name === "jackson-databind" && !isCross) {
        return { version: "2.17.0", bom: { organization: "com.fasterxml.jackson", name: "jackson-bom", version: "2.17.0" } };
      }
      return undefined;
    },
    resolveVariable: (group, org, name, isCross) => {
      if (group === "myproject" && org === "org.typelevel" && name === "cats-core" && isCross) {
        return { version: "2.10.0", variable: "catsVersion" };
      }
      return undefined;
    },
    pinFor: () => undefined,
    stale,
  };
}

describe("parseResolvedDecorations", () => {
  it("decorates a * dependency in a simple group", () => {
    const lines = [
      "myproject = [",
      '  "com.fasterxml.jackson.core:jackson-databind:*"',
      "]",
    ];
    const results = parseResolvedDecorations(lines, fakeLookup());
    expect(results).toHaveLength(1);
    expect(results[0].line).toBe(1);
    expect(results[0].text).toBe(" = 2.17.0");
    // afterCol points right after the closing quote
    expect(lines[1].slice(0, results[0].afterCol)).toBe('  "com.fasterxml.jackson.core:jackson-databind:*"');
  });

  it("decorates a {{variable}} dependency in an advanced group", () => {
    const lines = [
      "myproject {",
      "  dependencies = [",
      '    "org.typelevel::cats-core:{{catsVersion}}"',
      "  ]",
      "}",
    ];
    const results = parseResolvedDecorations(lines, fakeLookup());
    expect(results).toHaveLength(1);
    expect(results[0].line).toBe(2);
    expect(results[0].text).toBe(" = 2.10.0");
  });

  it("isolates resolution to the dependency's own group", () => {
    const lines = [
      "othergroup = [",
      '  "com.fasterxml.jackson.core:jackson-databind:*"',
      "]",
    ];
    // the fake only resolves for `myproject`, so a * in another group is not decorated
    expect(parseResolvedDecorations(lines, fakeLookup())).toEqual([]);
  });

  it("appends a (stale) marker when the dump is out of date", () => {
    const lines = [
      "myproject = [",
      '  "com.fasterxml.jackson.core:jackson-databind:*"',
      "]",
    ];
    const results = parseResolvedDecorations(lines, fakeLookup(true));
    expect(results[0].text).toBe(" = 2.17.0 (stale)");
  });

  it("emits nothing for versions that cannot be resolved", () => {
    const lines = [
      "myproject = [",
      '  "com.unknown:thing:*"',
      '  "org.typelevel::cats-core:2.10.0"',
      "]",
    ];
    expect(parseResolvedDecorations(lines, fakeLookup())).toEqual([]);
  });
});
