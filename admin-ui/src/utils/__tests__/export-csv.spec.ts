import { describe, expect, it, vi } from "vitest";
import { collectPages, sanitizeCell, toCsv } from "../export-csv";

describe("export csv", () => {
  it("neutralises spreadsheet formulas and quotes separators", () => {
    expect(sanitizeCell("=HYPERLINK(1)")).toBe("'=HYPERLINK(1)");
    expect(sanitizeCell("+1")).toBe("'+1");
    expect(sanitizeCell("-2")).toBe("'-2");
    expect(sanitizeCell("@cmd")).toBe("'@cmd");
    expect(sanitizeCell('a,"b"')).toBe('"a,""b"""');
    expect(sanitizeCell("line\nbreak")).toBe('"line\nbreak"');
    expect(sanitizeCell(null)).toBe("");
    expect(sanitizeCell(12)).toBe("12");
  });

  it("writes a header row then one CRLF line per row", () => {
    const csv = toCsv<{ a: string }>(
      [{ label: "编号", value: row => row.a }],
      [{ a: "x" }, { a: "y" }]
    );
    expect(csv).toBe("编号\r\nx\r\ny");
  });

  it("pages until the total and stops", async () => {
    const fetchPage = vi.fn(async (page: number, size: number) => ({
      items: Array.from({ length: page < 3 ? size : 5 }, (_, i) => (page - 1) * size + i),
      total: String(2 * size + 5),
      page,
      size
    }));
    const result = await collectPages(fetchPage, 5000, 10);
    expect(fetchPage).toHaveBeenCalledTimes(3);
    expect(result.rows).toHaveLength(25);
    expect(result.truncated).toBe(false);
  });

  it("caps the rows and reports truncation", async () => {
    const fetchPage = vi.fn(async (page: number, size: number) => ({
      items: Array.from({ length: size }, (_, i) => (page - 1) * size + i),
      total: "1000",
      page,
      size
    }));
    const result = await collectPages(fetchPage, 25, 10);
    expect(fetchPage).toHaveBeenCalledTimes(3);
    expect(result.rows).toHaveLength(25);
    expect(result.total).toBe(1000);
    expect(result.truncated).toBe(true);
  });
});
