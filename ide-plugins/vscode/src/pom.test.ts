import * as path from "node:path";
import { describe, it, expect } from "vitest";
import {
  getCoursierCachePath,
  extractRepositoryUrl,
  findMavenBases,
  resolveRepositoryUrl,
} from "./pom";

describe("getCoursierCachePath", () => {
  it("returns macOS default path", () => {
    const result = getCoursierCachePath({}, "darwin");
    expect(result).toContain(path.join("Library", "Caches", "Coursier", "v1"));
  });

  it("returns Linux default path", () => {
    const result = getCoursierCachePath({}, "linux");
    expect(result).toContain(path.join(".cache", "coursier", "v1"));
  });

  it("returns COURSIER_CACHE override when set", () => {
    const result = getCoursierCachePath({ COURSIER_CACHE: "/custom/cache" }, "darwin");
    expect(result).toBe("/custom/cache");
  });
});

describe("extractRepositoryUrl", () => {
  it("extracts URL from <scm><url>", () => {
    const pom = `
      <project>
        <scm>
          <url>https://github.com/foo/bar</url>
        </scm>
      </project>
    `;
    expect(extractRepositoryUrl(pom)).toBe("https://github.com/foo/bar");
  });

  it("extracts URL from top-level <url> when it looks like a repo", () => {
    const pom = `
      <project>
        <url>https://github.com/foo/bar</url>
      </project>
    `;
    expect(extractRepositoryUrl(pom)).toBe("https://github.com/foo/bar");
  });

  it("returns undefined for non-repo top-level <url> without <scm>", () => {
    const pom = `
      <project>
        <url>https://foo.org/docs</url>
      </project>
    `;
    expect(extractRepositoryUrl(pom)).toBeUndefined();
  });

  it("cleans scm:git: prefix and .git suffix", () => {
    const pom = `
      <project>
        <scm>
          <url>scm:git:https://github.com/foo/bar.git</url>
        </scm>
      </project>
    `;
    expect(extractRepositoryUrl(pom)).toBe("https://github.com/foo/bar");
  });

  it("returns undefined for git:// or ssh:// URLs", () => {
    const pom = `
      <project>
        <scm>
          <url>scm:git:git@github.com:foo/bar.git</url>
        </scm>
      </project>
    `;
    expect(extractRepositoryUrl(pom)).toBeUndefined();
  });

  it("returns undefined when no URL fields are present", () => {
    const pom = `
      <project>
        <groupId>com.example</groupId>
        <artifactId>foo</artifactId>
      </project>
    `;
    expect(extractRepositoryUrl(pom)).toBeUndefined();
  });

  it("normalizes http to https", () => {
    const pom = `
      <project>
        <scm>
          <url>http://github.com/foo/bar</url>
        </scm>
      </project>
    `;
    expect(extractRepositoryUrl(pom)).toBe("https://github.com/foo/bar");
  });

  it("prefers <scm><url> over top-level <url>", () => {
    const pom = `
      <project>
        <url>https://github.com/foo/docs</url>
        <scm>
          <url>https://github.com/foo/bar</url>
        </scm>
      </project>
    `;
    expect(extractRepositoryUrl(pom)).toBe("https://github.com/foo/bar");
  });
});

describe("findMavenBases", () => {
  it("finds base at depth 1 (maven2/)", () => {
    const tree: Record<string, string[]> = {
      "/cache/repo1.maven.org": ["maven2"],
      "/cache/repo1.maven.org/maven2": ["org", "com"],
    };
    const readdir = (dir: string) => tree[dir] ?? [];
    expect(findMavenBases("/cache/repo1.maven.org", "org", readdir)).toEqual([
      path.join("/cache/repo1.maven.org", "maven2"),
    ]);
  });

  it("finds base at depth 3 (content/repositories/releases/)", () => {
    const tree: Record<string, string[]> = {
      "/cache/oss.sonatype.org": ["content"],
      "/cache/oss.sonatype.org/content": ["repositories"],
      "/cache/oss.sonatype.org/content/repositories": ["releases"],
      "/cache/oss.sonatype.org/content/repositories/releases": ["org", "com"],
    };
    const readdir = (dir: string) => tree[dir] ?? [];
    expect(findMavenBases("/cache/oss.sonatype.org", "org", readdir)).toEqual([
      path.join("/cache/oss.sonatype.org", "content", "repositories", "releases"),
    ]);
  });

  it("returns empty array when not found", () => {
    const readdir = (_dir: string): string[] => [];
    expect(findMavenBases("/cache/unknown.host", "org", readdir)).toEqual([]);
  });

  it("finds multiple bases under the same host (JFrog Artifactory)", () => {
    const tree: Record<string, string[]> = {
      "/cache/jfrog.io": ["artifactory"],
      "/cache/jfrog.io/artifactory": ["ivy", "maven", "custom-libs"],
      "/cache/jfrog.io/artifactory/ivy": ["com.example"],
      "/cache/jfrog.io/artifactory/maven": ["com"],
      "/cache/jfrog.io/artifactory/custom-libs": ["com"],
    };
    const readdir = (dir: string) => tree[dir] ?? [];
    expect(findMavenBases("/cache/jfrog.io", "com", readdir)).toEqual([
      path.join("/cache/jfrog.io", "artifactory", "maven"),
      path.join("/cache/jfrog.io", "artifactory", "custom-libs"),
    ]);
  });
});

describe("resolveRepositoryUrl", () => {
  const cachePath = "/fake/cache";

  function buildMockFs(structure: Record<string, string[] | string>) {
    const readdir = (dir: string): string[] => {
      const val = structure[dir];
      if (Array.isArray(val)) return val;
      throw new Error(`ENOENT: ${dir}`);
    };

    const readFile = (file: string): string | undefined => {
      const val = structure[file];
      return typeof val === "string" ? val : undefined;
    };

    return { readdir, readFile };
  }

  it("resolves repo URL from Scala dependency POM", () => {
    const { readdir, readFile } = buildMockFs({
      "/fake/cache/https": ["repo1.maven.org"],
      "/fake/cache/https/repo1.maven.org": ["maven2"],
      "/fake/cache/https/repo1.maven.org/maven2": ["org"],
      "/fake/cache/https/repo1.maven.org/maven2/org": ["typelevel"],
      "/fake/cache/https/repo1.maven.org/maven2/org/typelevel": ["cats-core_3"],
      "/fake/cache/https/repo1.maven.org/maven2/org/typelevel/cats-core_3": ["2.10.0"],
      "/fake/cache/https/repo1.maven.org/maven2/org/typelevel/cats-core_3/2.10.0": [
        "cats-core_3-2.10.0.pom",
      ],
      "/fake/cache/https/repo1.maven.org/maven2/org/typelevel/cats-core_3/2.10.0/cats-core_3-2.10.0.pom":
        '<project><scm><url>https://github.com/typelevel/cats</url></scm></project>',
    });

    const dep = {
      org: "org.typelevel",
      separator: "::",
      artifact: "cats-core",
      version: "2.10.0",
      matchStart: 0,
      matchEnd: 30,
    };

    expect(resolveRepositoryUrl(dep, cachePath, readdir, readFile)).toBe(
      "https://github.com/typelevel/cats"
    );
  });

  it("searches multiple repos", () => {
    const { readdir, readFile } = buildMockFs({
      "/fake/cache/https": ["repo1.maven.org", "oss.sonatype.org"],
      // repo1 has nothing useful
      "/fake/cache/https/repo1.maven.org": ["maven2"],
      "/fake/cache/https/repo1.maven.org/maven2": ["com"],
      // sonatype has the artifact
      "/fake/cache/https/oss.sonatype.org": ["content"],
      "/fake/cache/https/oss.sonatype.org/content": ["repositories"],
      "/fake/cache/https/oss.sonatype.org/content/repositories": ["releases"],
      "/fake/cache/https/oss.sonatype.org/content/repositories/releases": ["com"],
      "/fake/cache/https/oss.sonatype.org/content/repositories/releases/com": ["example"],
      "/fake/cache/https/oss.sonatype.org/content/repositories/releases/com/example": ["lib"],
      "/fake/cache/https/oss.sonatype.org/content/repositories/releases/com/example/lib": ["1.0"],
      "/fake/cache/https/oss.sonatype.org/content/repositories/releases/com/example/lib/1.0": [
        "lib-1.0.pom",
      ],
      "/fake/cache/https/oss.sonatype.org/content/repositories/releases/com/example/lib/1.0/lib-1.0.pom":
        '<project><scm><url>https://github.com/example/lib</url></scm></project>',
    });

    const dep = {
      org: "com.example",
      separator: ":",
      artifact: "lib",
      version: "1.0",
      matchStart: 0,
      matchEnd: 20,
    };

    expect(resolveRepositoryUrl(dep, cachePath, readdir, readFile)).toBe(
      "https://github.com/example/lib"
    );
  });

  it("returns undefined when POM is missing", () => {
    const { readdir, readFile } = buildMockFs({
      "/fake/cache/https": ["repo1.maven.org"],
      "/fake/cache/https/repo1.maven.org": ["maven2"],
      "/fake/cache/https/repo1.maven.org/maven2": ["org"],
    });

    const dep = {
      org: "org.missing",
      separator: "::",
      artifact: "nope",
      version: "1.0",
      matchStart: 0,
      matchEnd: 20,
    };

    expect(resolveRepositoryUrl(dep, cachePath, readdir, readFile)).toBeUndefined();
  });

  it("uses sbt-plugin suffix for sbt plugins", () => {
    const { readdir, readFile } = buildMockFs({
      "/fake/cache/https": ["repo1.maven.org"],
      "/fake/cache/https/repo1.maven.org": ["maven2"],
      "/fake/cache/https/repo1.maven.org/maven2": ["ch"],
      "/fake/cache/https/repo1.maven.org/maven2/ch": ["epfl"],
      "/fake/cache/https/repo1.maven.org/maven2/ch/epfl": ["scala"],
      "/fake/cache/https/repo1.maven.org/maven2/ch/epfl/scala": ["sbt-scalafix_2.12_1.0"],
      "/fake/cache/https/repo1.maven.org/maven2/ch/epfl/scala/sbt-scalafix_2.12_1.0": ["0.14.5"],
      "/fake/cache/https/repo1.maven.org/maven2/ch/epfl/scala/sbt-scalafix_2.12_1.0/0.14.5": [
        "sbt-scalafix_2.12_1.0-0.14.5.pom",
      ],
      "/fake/cache/https/repo1.maven.org/maven2/ch/epfl/scala/sbt-scalafix_2.12_1.0/0.14.5/sbt-scalafix_2.12_1.0-0.14.5.pom":
        '<project><scm><url>https://github.com/scalacenter/scalafix</url></scm></project>',
    });

    const dep = {
      org: "ch.epfl.scala",
      separator: ":",
      artifact: "sbt-scalafix",
      version: "0.14.5",
      config: "sbt-plugin",
      matchStart: 0,
      matchEnd: 40,
    };

    expect(resolveRepositoryUrl(dep, cachePath, readdir, readFile)).toBe(
      "https://github.com/scalacenter/scalafix"
    );
  });

  it("tries multiple maven bases within the same host", () => {
    const { readdir, readFile } = buildMockFs({
      "/fake/cache/https": ["jfrog.io"],
      "/fake/cache/https/jfrog.io": ["artifactory"],
      "/fake/cache/https/jfrog.io/artifactory": ["maven", "custom-libs"],
      // maven has com/ but no POM for this artifact
      "/fake/cache/https/jfrog.io/artifactory/maven": ["com"],
      "/fake/cache/https/jfrog.io/artifactory/maven/com": ["acme"],
      "/fake/cache/https/jfrog.io/artifactory/maven/com/acme": [],
      // custom-libs has the actual artifact with SCM info
      "/fake/cache/https/jfrog.io/artifactory/custom-libs": ["com"],
      "/fake/cache/https/jfrog.io/artifactory/custom-libs/com": ["acme"],
      "/fake/cache/https/jfrog.io/artifactory/custom-libs/com/acme": ["my-lib_3"],
      "/fake/cache/https/jfrog.io/artifactory/custom-libs/com/acme/my-lib_3": ["1.0.0"],
      "/fake/cache/https/jfrog.io/artifactory/custom-libs/com/acme/my-lib_3/1.0.0": [
        "my-lib_3-1.0.0.pom",
      ],
      "/fake/cache/https/jfrog.io/artifactory/custom-libs/com/acme/my-lib_3/1.0.0/my-lib_3-1.0.0.pom":
        '<project><scm><url>https://github.com/acme/my-lib</url></scm></project>',
    });

    const dep = {
      org: "com.acme",
      separator: "::",
      artifact: "my-lib",
      version: "1.0.0",
      matchStart: 0,
      matchEnd: 30,
    };

    expect(resolveRepositoryUrl(dep, cachePath, readdir, readFile)).toBe(
      "https://github.com/acme/my-lib"
    );
  });
});
