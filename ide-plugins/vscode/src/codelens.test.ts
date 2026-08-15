import { describe, it, expect } from "vitest";
import { parseCodeLenses } from "./codelens";

describe("parseCodeLenses", () => {
  describe("project detection", () => {
    it("detects simple `lazy val name = project`", () => {
      const lines = ["lazy val core = project"];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(1);
      expect(result[0].projectName).toBe("core");
      expect(result[0].line).toBe(0);
    });

    it("detects `lazy val name = (project in file(...))`", () => {
      const lines = ['lazy val core = (project in file("core"))'];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(1);
      expect(result[0].projectName).toBe("core");
    });

    it("detects `lazy val name = project.settings(...)`", () => {
      const lines = ["lazy val core = project.settings(commonSettings)"];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(1);
      expect(result[0].projectName).toBe("core");
    });

    it("detects `lazy val name = project.in(file(...))`", () => {
      const lines = ['lazy val core = project.in(file("core"))'];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(1);
      expect(result[0].projectName).toBe("core");
    });

    it("detects `lazy val name = module`", () => {
      const lines = ["lazy val core = module"];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(1);
      expect(result[0].projectName).toBe("core");
    });

    it("detects `lazy val name = module.settings(...)`", () => {
      const lines = ["lazy val core = module.settings(commonSettings)"];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(1);
      expect(result[0].projectName).toBe("core");
    });

    it("detects indented definitions", () => {
      const lines = ["  lazy val core = project"];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(1);
      expect(result[0].projectName).toBe("core");
    });

    it("detects multiple projects", () => {
      const lines = [
        "lazy val core = project",
        "",
        "lazy val api = project.settings(commonSettings)",
      ];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(2);
      expect(result[0].projectName).toBe("core");
      expect(result[0].line).toBe(0);
      expect(result[1].projectName).toBe("api");
      expect(result[1].line).toBe(2);
    });
  });

  describe("backtick-quoted names", () => {
    it("extracts backtick-quoted name without backticks", () => {
      const lines = ["lazy val `my-project` = project"];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(1);
      expect(result[0].projectName).toBe("my-project");
    });

    it("extracts backtick-quoted name with dots", () => {
      const lines = ["lazy val `my.project` = project"];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(1);
      expect(result[0].projectName).toBe("my.project");
    });
  });

  describe("group matching", () => {
    it("sets groupExists to true when group exists", () => {
      const lines = ["lazy val core = project"];
      const result = parseCodeLenses(lines, ["core", "api"]);
      expect(result[0].groupExists).toBe(true);
    });

    it("sets groupExists to false when group does not exist", () => {
      const lines = ["lazy val core = project"];
      const result = parseCodeLenses(lines, ["api"]);
      expect(result[0].groupExists).toBe(false);
    });

    it("matches backtick-quoted names against group names", () => {
      const lines = ["lazy val `my-project` = project"];
      const result = parseCodeLenses(lines, ["my-project"]);
      expect(result[0].groupExists).toBe(true);
    });
  });

  describe("non-matching lines", () => {
    it("ignores commented lines", () => {
      const lines = ["// lazy val core = project"];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(0);
    });

    it("ignores val without lazy", () => {
      const lines = ["val core = project"];
      const result = parseCodeLenses(lines, []);
      expect(result).toHaveLength(0);
    });

    it("returns empty for empty input", () => {
      const result = parseCodeLenses([], []);
      expect(result).toHaveLength(0);
    });
  });

  describe("real-world example", () => {
    it("handles multi-project build with mixed group availability", () => {
      const lines = [
        'import sbt._',
        '',
        'lazy val root = (project in file("."))',
        '  .aggregate(core, api, `my-plugin`, `sbt-dependencies`)',
        '',
        'lazy val core = project',
        '  .settings(commonSettings)',
        '',
        'lazy val api = project.in(file("api"))',
        '',
        'lazy val `my-plugin` = project.settings(pluginSettings)',
        '',
        'lazy val `sbt-dependencies` = module',
      ];
      const groups = ["core", "my-plugin", "sbt-dependencies"];
      const result = parseCodeLenses(lines, groups);
      expect(result).toHaveLength(5);

      expect(result[0].projectName).toBe("root");
      expect(result[0].groupExists).toBe(false);
      expect(result[0].line).toBe(2);

      expect(result[1].projectName).toBe("core");
      expect(result[1].groupExists).toBe(true);
      expect(result[1].line).toBe(5);

      expect(result[2].projectName).toBe("api");
      expect(result[2].groupExists).toBe(false);
      expect(result[2].line).toBe(8);

      expect(result[3].projectName).toBe("my-plugin");
      expect(result[3].groupExists).toBe(true);
      expect(result[3].line).toBe(10);

      expect(result[4].projectName).toBe("sbt-dependencies");
      expect(result[4].groupExists).toBe(true);
      expect(result[4].line).toBe(12);
    });
  });
});
