import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * L-122 (task 135): filter dropdowns inside ListSearch must use the shared width classes
 * (`.filter-w-sm` / `.filter-w-md` / `.filter-w-lg`, review R-21) instead of inline pixel widths,
 * so the same kind of control keeps one width within and across pages. The pattern accepts the
 * spelling variants reviewers flagged: single or double quotes, a `:style` binding with a literal
 * width, and no space after the colon. `min-width` and friends must not match.
 */
const viewsDir = resolve(__dirname, "..");

function vueFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...vueFiles(full));
    else if (name.endsWith(".vue")) out.push(full);
  }
  return out;
}

const INLINE_WIDTH = /(?<![\w-])width:\s*\d+px/;

describe("L-122: ListSearch filter selects use shared width classes", () => {
  it.each([
    ['style="width: 180px"', true],
    ["style='width:180px'", true],
    [":style=\"true ? 'width: 180px' : ''\"", true],
    ['style="width:100px"', true],
    ['style="width: 100%"', false],
    ['style="min-width: 180px"', false],
    ['style="max-width:180px"', false]
  ])("detects inline pixel width in %j", (snippet, shouldMatch) => {
    expect(INLINE_WIDTH.test(snippet)).toBe(shouldMatch);
  });

  it("keeps inline pixel widths out of every select rendered inside a ListSearch page", () => {
    const offenders: string[] = [];
    for (const file of vueFiles(viewsDir)) {
      const source = readFileSync(file, "utf8");
      if (!source.includes("ListSearch")) continue;
      // Simple pairing: every <el-select …> block until the matching close tag.
      const blocks = source.match(/<el-select[\s\S]*?<\/el-select>/g) ?? [];
      for (const block of blocks) {
        if (INLINE_WIDTH.test(block)) {
          offenders.push(`${relative(viewsDir, file)}: ${block.match(INLINE_WIDTH)?.[0]}`);
        }
      }
    }
    expect(offenders, `Inline pixel widths on filter selects:\n${offenders.join("\n")}`).toEqual(
      []
    );
  });
});
