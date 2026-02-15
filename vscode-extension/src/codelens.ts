export interface CodeLensData {
  line: number;
  projectName: string;
  groupExists: boolean;
}

const projectPattern = /^\s*lazy\s+val\s+(?:`([^`]+)`|(\w+))\s*=/;

export function parseCodeLenses(
  buildSbtLines: string[],
  groupNames: string[]
): CodeLensData[] {
  const groupSet = new Set(groupNames);
  const results: CodeLensData[] = [];

  for (let i = 0; i < buildSbtLines.length; i++) {
    const match = projectPattern.exec(buildSbtLines[i]);
    if (match) {
      const projectName = match[1] ?? match[2];
      results.push({
        line: i,
        projectName,
        groupExists: groupSet.has(projectName),
      });
    }
  }

  return results;
}
