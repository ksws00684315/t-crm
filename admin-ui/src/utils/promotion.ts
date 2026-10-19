import type { DisplayRelationStatus, RelationStatus } from "@/api/types";
import { formatRate } from "@/utils/rate";

export const RELATION_STATUS_OPTIONS: readonly RelationStatus[] = [
  "PENDING_PROMOTION",
  "PROMOTING",
  "INVALID"
];
export const DISPLAY_RELATION_STATUSES: readonly DisplayRelationStatus[] = [
  "PENDING_PROMOTION",
  "PROMOTING",
  "INVALID",
  "PENDING_UPDATE"
];

export function relationStatusLabelKey(status: DisplayRelationStatus): string {
  return `kol.relationStatusOption.${status}`;
}

export function relationTagType(
  status: DisplayRelationStatus
): "success" | "primary" | "warning" | "info" | "danger" {
  switch (status) {
    case "PENDING_PROMOTION":
      return "primary";
    case "PROMOTING":
      return "success";
    case "INVALID":
      return "info";
    case "PENDING_UPDATE":
      return "danger";
  }
}

export interface PromotionFieldDiff {
  key: string;
  from: string;
  to: string;
}

const RECORD_FIELDS = [
  "defaultRate",
  "inviteCode",
  "platforms",
  "platformAddress",
  "jumpTemplateId",
  "sheetUrl",
  "relationStatus",
  "reviewStatus",
  "name",
  "phone",
  "whatsapp",
  "primaryPlatform",
  "primaryHandle",
  "country",
  "remark"
];

function renderValue(key: string, value: unknown): string {
  if (value === null || value === undefined || value === "") return "-";
  if (key === "defaultRate") return formatRate(typeof value === "string" ? value : null);
  if (Array.isArray(value))
    return value
      .map(item => {
        if (!item || typeof item !== "object") return String(item);
        const platform = item as { templateName?: string; address?: string };
        return [platform.templateName, platform.address].filter(Boolean).join(" — ");
      })
      .join("、");
  return String(value);
}

export function promotionFieldDiffs(
  before: Record<string, unknown> | null,
  after: Record<string, unknown> | null
): PromotionFieldDiff[] {
  const keys = [
    ...new Set([...RECORD_FIELDS, ...Object.keys(before ?? {}), ...Object.keys(after ?? {})])
  ];
  return keys.flatMap(key => {
    const from = before?.[key];
    const to = after?.[key];
    if (JSON.stringify(from ?? null) === JSON.stringify(to ?? null)) return [];
    return [{ key, from: renderValue(key, from), to: renderValue(key, to) }];
  });
}

export function missingPromotionFields(payload: unknown): string[] {
  const source = Array.isArray(payload)
    ? payload
    : payload && typeof payload === "object" && Array.isArray((payload as { data?: unknown }).data)
      ? (payload as { data: unknown[] }).data
      : [];
  return [...new Set(source.filter((field): field is string => typeof field === "string"))];
}

export interface PromotionCompletenessInput {
  defaultRate?: string | null;
  inviteCode?: string | null;
  platforms?: Array<{ templateId: string; address: string }>;
  jumpTemplateId?: string | null;
  sheetUrl?: string | null;
  review?: boolean;
}

export function incompletePromotionFields(config: PromotionCompletenessInput): string[] {
  const missing: string[] = [];
  if (!config.defaultRate) missing.push("defaultRate");
  if (!config.inviteCode) missing.push("inviteCode");
  if (!config.platforms?.length || config.platforms.some(platform => !platform.templateId))
    missing.push("platforms");
  if (config.platforms?.some(platform => !platform.address)) missing.push("platformAddress");
  if (config.platforms?.length && !config.jumpTemplateId) missing.push("jumpTemplateId");
  if (!config.sheetUrl) missing.push("sheetUrl");
  if (config.review === false) missing.push("review");
  return missing;
}
