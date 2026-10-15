import type { PageResult } from "@/api/types";
import { saveBlob } from "@/utils/download";

/**
 * Client-side list export (research report P0-1). Pages through the same list API the table uses,
 * so the backend applies the same authentication, tenant and data-scope rules as on screen.
 * Rows are capped at EXPORT_MAX_ROWS; larger exports need a server-side job (deferred).
 */
export const EXPORT_MAX_ROWS = 5000;
/** Matches the backend `@Max(100)` on every list query. */
export const EXPORT_PAGE_SIZE = 100;

export interface ExportColumn<T> {
  label: string;
  value: (row: T) => unknown;
}

/**
 * Spreadsheet apps evaluate cells starting with = + - @ (and tab / CR) as formulas (CSV injection).
 * Such cells get a leading apostrophe so they stay text.
 */
export function sanitizeCell(value: unknown): string {
  if (value == null) return "";
  let text = String(value);
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  if (/[",\r\n]/.test(text)) text = `"${text.replace(/"/g, '""')}"`;
  return text;
}

export function toCsv<T>(columns: readonly ExportColumn<T>[], rows: readonly T[]): string {
  const lines = [columns.map(column => sanitizeCell(column.label)).join(",")];
  for (const row of rows)
    lines.push(columns.map(column => sanitizeCell(column.value(row))).join(","));
  return lines.join("\r\n");
}

export interface CollectResult<T> {
  rows: T[];
  total: number;
  truncated: boolean;
}

/** Fetches pages sequentially until `total` or the row cap is reached. */
export async function collectPages<T>(
  fetchPage: (page: number, size: number) => Promise<PageResult<T>>,
  maxRows: number = EXPORT_MAX_ROWS,
  pageSize: number = EXPORT_PAGE_SIZE
): Promise<CollectResult<T>> {
  const rows: T[] = [];
  let total = 0;
  for (let page = 1; rows.length < maxRows; page += 1) {
    const result = await fetchPage(page, pageSize);
    total = Number(result.total);
    rows.push(...result.items);
    if (result.items.length < pageSize || rows.length >= total) break;
  }
  const truncated = total > maxRows;
  return { rows: rows.slice(0, maxRows), total, truncated };
}

/** UTF-8 with BOM so Excel opens Chinese text correctly. */
export function saveCsv(fileBase: string, csv: string, now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`;
  const fileName = `${fileBase}-${stamp}.csv`;
  saveBlob(new Blob(["\uFEFF", csv], { type: "text/csv;charset=utf-8" }), fileName);
  return fileName;
}
