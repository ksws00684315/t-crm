/*
 * Promotion status display and change-record helpers (task 25, ADR-023 §2–§3). Pure functions so
 * the list, the config drawer and the status dialog share one set of rules.
 */

import type { DisplayStatus, PromotionStatus } from "@/api/types";
import { formatRate } from "@/utils/rate";

/** Every stored status, in the order pickers show them. */
export const PROMOTION_STATUSES: readonly PromotionStatus[] = [
  "PENDING_REVIEW",
  "PENDING_PROMOTION",
  "PROMOTING",
  "ENDED"
];

/** Stored statuses plus the computed "no active BD" state shown in lists. */
export const DISPLAY_STATUSES: readonly DisplayStatus[] = [...PROMOTION_STATUSES, "PENDING_UPDATE"];

/** Message-pack key of a shown status. */
export function promotionStatusLabelKey(status: DisplayStatus): string {
  return `kol.promotionStatusOption.${status}`;
}

/** One distinct tag color per shown status (task 25b §3.2). */
export function promotionTagType(
  status: DisplayStatus
): "success" | "primary" | "warning" | "info" | "danger" {
  switch (status) {
    case "PENDING_REVIEW":
      return "warning";
    case "PENDING_PROMOTION":
      return "primary";
    case "PROMOTING":
      return "success";
    case "ENDED":
      return "info";
    default:
      return "danger";
  }
}

/** Keys a promotion record may hold; unknown keys from a newer backend are still rendered. */
const RECORD_FIELD_KEYS = [
  "defaultRate",
  "platformTemplateIds",
  "landingUrl",
  "inviteCode",
  "sheetUrl",
  "status"
] as const;

/** One changed field of a record: raw values already rendered to display strings. */
export interface PromotionFieldDiff {
  key: string;
  from: string;
  to: string;
}

function renderValue(key: string, value: unknown, platformName: (id: string) => string): string {
  if (value === null || value === undefined || value === "") return "-";
  if (key === "defaultRate") return formatRate(typeof value === "string" ? value : null);
  if (key === "platformTemplateIds" && Array.isArray(value)) {
    const names = value.map(item => platformName(String(item)));
    return names.length > 0 ? names.join("、") : "-";
  }
  return String(value);
}

function sameValue(key: string, before: unknown, after: unknown): boolean {
  if (key === "platformTemplateIds") {
    const left = Array.isArray(before) ? [...before].sort().join(",") : String(before ?? "");
    const right = Array.isArray(after) ? [...after].sort().join(",") : String(after ?? "");
    return left === right;
  }
  return String(before ?? "") === String(after ?? "");
}

/**
 * Field-by-field changes between the `before` and `after` snapshots of a record, in the fixed
 * field order. Status values stay as label keys so the caller can translate them per language.
 */
export function promotionFieldDiffs(
  before: Record<string, unknown> | null,
  after: Record<string, unknown> | null,
  platformName: (id: string) => string = id => id
): PromotionFieldDiff[] {
  const keys = new Set<string>([
    ...RECORD_FIELD_KEYS,
    ...Object.keys(before ?? {}),
    ...Object.keys(after ?? {})
  ]);
  const diffs: PromotionFieldDiff[] = [];
  for (const key of keys) {
    const from = before ? before[key] : undefined;
    const to = after ? after[key] : undefined;
    if (sameValue(key, from, to)) continue;
    diffs.push({
      key,
      from: renderValue(key, from, platformName),
      to: renderValue(key, to, platformName)
    });
  }
  return diffs;
}

/**
 * Field keys the backend listed in a 422 `KOL_PROMOTION_CONFIG_INCOMPLETE`. The contract does not
 * fix the payload shape, so several plausible ones are accepted; anything else yields an empty
 * list and the caller shows the plain message.
 */
export function missingPromotionFields(payload: unknown): string[] {
  const source: unknown[] = Array.isArray(payload)
    ? payload
    : isStringArray((payload as { missingFields?: unknown })?.missingFields)
      ? ((payload as { missingFields: string[] }).missingFields as unknown[])
      : isStringArray((payload as { fields?: unknown })?.fields)
        ? ((payload as { fields: string[] }).fields as unknown[])
        : [];
  const keys: string[] = [];
  for (const item of source) {
    if (typeof item === "string") keys.push(item);
    else if (
      item &&
      typeof item === "object" &&
      typeof (item as { field?: unknown }).field === "string"
    )
      keys.push((item as { field: string }).field);
  }
  return [...new Set(keys)];
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every(item => typeof item === "string");
}

/** The four fields that must be set before a creator may promote (25a §4.2). */
export interface PromotionCompletenessInput {
  defaultRate?: string | null;
  platformTemplateIds?: string[] | null;
  landingUrl?: string | null;
  inviteCode?: string | null;
}

/** Client mirror of the backend rule; the 422 answer stays the authority. */
export function incompletePromotionFields(config: PromotionCompletenessInput): string[] {
  const missing: string[] = [];
  if (!config.defaultRate) missing.push("defaultRate");
  if (!config.platformTemplateIds || config.platformTemplateIds.length === 0)
    missing.push("platformTemplateIds");
  if (!config.landingUrl) missing.push("landingUrl");
  if (!config.inviteCode) missing.push("inviteCode");
  return missing;
}
