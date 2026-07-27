import { describe, it, expect } from "vitest";
import * as path from "node:path";
import {
  dumpPathsFor,
  parseResolutionsDump,
  ResolutionsIndex,
  ResolutionsDump,
} from "./resolutions";

describe("dumpPathsFor", () => {
  it("derives the main and meta dump paths from the conf path", () => {
    const conf = path.join("/repo", "project", "dependencies.conf");
    const { main, meta } = dumpPathsFor(conf);
    expect(main).toBe(path.join("/repo", "target", "sbt-dependencies", ".sbt-resolutions"));
    expect(meta).toBe(path.join("/repo", "project", "target", "sbt-dependencies", ".sbt-resolutions"));
  });
});

describe("parseResolutionsDump", () => {
  it("parses a valid dump", () => {
    const dump = parseResolutionsDump('{"version":1,"boms":{},"projects":{}}');
    expect(dump).toBeDefined();
    expect(dump!.version).toBe(1);
  });

  it("returns undefined for an unsupported version", () => {
    expect(parseResolutionsDump('{"version":2,"boms":{},"projects":{}}')).toBeUndefined();
  });

  it("returns undefined for malformed JSON", () => {
    expect(parseResolutionsDump("{not json")).toBeUndefined();
  });

  it("returns undefined when boms or projects are missing", () => {
    expect(parseResolutionsDump('{"version":1}')).toBeUndefined();
  });
});

describe("ResolutionsIndex", () => {
  const dump: ResolutionsDump = {
    version: 1,
    boms: {
      "com.fasterxml.jackson:jackson-bom:2.17.0@2.13": {
        organization: "com.fasterxml.jackson",
        name: "jackson-bom",
        version: "2.17.0",
        entries: [
          { organization: "com.fasterxml.jackson.core", name: "jackson-databind", version: "2.17.0" },
          { organization: "org.typelevel", name: "cats-core_2.13", version: "2.10.0" },
        ],
      },
      "com.example:other-bom:1.0.0@2.13": {
        organization: "com.example",
        name: "other-bom",
        version: "1.0.0",
        entries: [{ organization: "com.fasterxml.jackson.core", name: "jackson-databind", version: "9.9.9" }],
      },
    },
    projects: {
      // jackson-bom is declared first, so it wins the shared jackson-databind pin
      myproject: {
        scalaBinaryVersions: ["2.13", "3"],
        boms: ["com.fasterxml.jackson:jackson-bom:2.17.0@2.13", "com.example:other-bom:1.0.0@2.13"],
        variables: [
          { organization: "org.typelevel", name: "cats-effect", cross: true, variable: "ceVersion", version: "3.5.4" },
        ],
      },
    },
  };

  const metaDump: ResolutionsDump = {
    version: 1,
    boms: {
      "com.example:plugin-bom:1.0.0@2.12": {
        organization: "com.example",
        name: "plugin-bom",
        version: "1.0.0",
        entries: [{ organization: "com.example", name: "sbt-thing", version: "0.4.0" }],
      },
    },
    projects: {
      "sbt-build": {
        scalaBinaryVersions: ["2.12"],
        boms: ["com.example:plugin-bom:1.0.0@2.12"],
        variables: [],
      },
    },
  };

  it("resolves a plain (Java) * against the exact artifact name", () => {
    const index = new ResolutionsIndex(dump, undefined);
    const result = index.resolveWildcard("myproject", "com.fasterxml.jackson.core", "jackson-databind", false);
    expect(result?.version).toBe("2.17.0");
    expect(result?.bom.name).toBe("jackson-bom");
  });

  it("resolves a cross (Scala) * against the Scala-suffixed artifact name", () => {
    const index = new ResolutionsIndex(dump, undefined);
    const result = index.resolveWildcard("myproject", "org.typelevel", "cats-core", true);
    expect(result?.version).toBe("2.10.0");
  });

  it("applies first-BOM-wins when two BOMs pin the same artifact", () => {
    const index = new ResolutionsIndex(dump, undefined);
    // both jackson-bom and other-bom pin jackson-databind; jackson-bom is declared first
    const result = index.resolveWildcard("myproject", "com.fasterxml.jackson.core", "jackson-databind", false);
    expect(result?.version).toBe("2.17.0");
  });

  it("returns undefined for an unknown group", () => {
    const index = new ResolutionsIndex(dump, undefined);
    expect(index.resolveWildcard("nope", "org", "name", false)).toBeUndefined();
  });

  it("returns undefined for an artifact no visible BOM pins", () => {
    const index = new ResolutionsIndex(dump, undefined);
    expect(index.resolveWildcard("myproject", "com.unknown", "thing", false)).toBeUndefined();
  });

  it("routes the sbt-build group to the meta dump", () => {
    const index = new ResolutionsIndex(dump, metaDump);
    const result = index.resolveWildcard("sbt-build", "com.example", "sbt-thing", false);
    expect(result?.version).toBe("0.4.0");
  });

  it("resolves a variable matching organization, name and cross", () => {
    const index = new ResolutionsIndex(dump, undefined);
    const result = index.resolveVariable("myproject", "org.typelevel", "cats-effect", true);
    expect(result).toEqual({ version: "3.5.4", variable: "ceVersion" });
  });

  it("does not resolve a variable when the cross flag differs", () => {
    const index = new ResolutionsIndex(dump, undefined);
    expect(index.resolveVariable("myproject", "org.typelevel", "cats-effect", false)).toBeUndefined();
  });

  it("exposes hasData and a stale-carrying lookup", () => {
    expect(new ResolutionsIndex(undefined, undefined).hasData).toBe(false);
    const lookup = new ResolutionsIndex(dump, undefined).asLookup(true);
    expect(lookup.stale).toBe(true);
    expect(lookup.pinFor("myproject", "com.fasterxml.jackson.core", "jackson-databind", false)?.version).toBe("2.17.0");
  });

  it("exposes the source hash, preferring the main dump", () => {
    expect(new ResolutionsIndex({ ...dump, sourceHash: "abc" }, undefined).sourceHash).toBe("abc");
    expect(new ResolutionsIndex(undefined, { ...metaDump, sourceHash: "meta" }).sourceHash).toBe("meta");
    expect(new ResolutionsIndex(dump, undefined).sourceHash).toBeUndefined();
  });
});
