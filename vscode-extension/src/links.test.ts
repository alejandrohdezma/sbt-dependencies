import { describe, it, expect } from "vitest";
import { parseDocumentLinks } from "./links";

describe("parseDocumentLinks", () => {
  it("returns link for Scala dependency", () => {
    const lines = ['  "org.typelevel::cats-core:2.10.0"'];
    const links = parseDocumentLinks(lines);
    expect(links).toHaveLength(1);
    expect(links[0].url).toBe("https://mvnrepository.com/artifact/org.typelevel/cats-core");
  });

  it("returns link for Java dependency", () => {
    const lines = ['  "com.typesafe:config:1.4.3"'];
    const links = parseDocumentLinks(lines);
    expect(links).toHaveLength(1);
    expect(links[0].url).toBe("https://mvnrepository.com/artifact/com.typesafe/config");
  });

  it("uses _2.12_1.0 suffix for sbt-plugin", () => {
    const lines = ['  "ch.epfl.scala:sbt-scalafix:0.14.5:sbt-plugin"'];
    const links = parseDocumentLinks(lines);
    expect(links).toHaveLength(1);
    expect(links[0].url).toBe("https://mvnrepository.com/artifact/ch.epfl.scala/sbt-scalafix_2.12_1.0");
  });

  it("returns no links for comment lines", () => {
    const lines = [
      "// this is a comment",
      "# another comment",
    ];
    expect(parseDocumentLinks(lines)).toEqual([]);
  });

  it("returns no links for blank lines", () => {
    const lines = ["", "  "];
    expect(parseDocumentLinks(lines)).toEqual([]);
  });

  it("returns no links for header lines", () => {
    const lines = [
      "my-group = [",
      "my-group {",
    ];
    expect(parseDocumentLinks(lines)).toEqual([]);
  });

  it("range matches matchStart to matchEnd", () => {
    const lines = ['  "org.typelevel::cats-core:2.10.0"'];
    const links = parseDocumentLinks(lines);
    expect(links).toHaveLength(1);
    expect(links[0].range.startLine).toBe(0);
    expect(links[0].range.endLine).toBe(0);
    expect(links[0].range.startCol).toBe(3);
    expect(links[0].range.endCol).toBe(3 + "org.typelevel::cats-core:2.10.0".length);
  });

  it("returns multiple links for multiple dependencies", () => {
    const lines = [
      'my-group = [',
      '  "org.typelevel::cats-core:2.10.0"',
      '  "co.fs2::fs2-core:^3.9.4"',
      ']',
    ];
    const links = parseDocumentLinks(lines);
    expect(links).toHaveLength(2);
    expect(links[0].url).toContain("cats-core");
    expect(links[1].url).toContain("fs2-core");
  });
});
