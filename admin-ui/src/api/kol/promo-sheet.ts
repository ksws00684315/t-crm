import { http } from "../http";

/** Upload size limit of the promo-sheet endpoint (task 26 / ADR-024), same as the backend. */
export const PROMO_SHEET_MAX_BYTES = 300 * 1024 * 1024;

/** Why a promo-sheet row produced no mapping. */
export type PromoSheetSkipReason =
  "IN_SHEET" | "NON_WEIDIAN" | "NO_ITEM_ID" | "SHOP_NOT_REGISTERED";

/** A skipped row sample: worksheet, 1-based row, reason code. */
export interface PromoSheetSkipSample {
  sheet: string;
  row: number;
  reason: PromoSheetSkipReason;
}

/** A mapping that confirmation would create. */
export interface PromoSheetNewItem {
  shopName: string;
  shopWeidianId: string;
  weidianItemId: string;
  itemName: string | null;
  productCode: string | null;
}

/** A conflicting item; owner fields are null unless the viewer is a super administrator. */
export interface PromoSheetConflict {
  shopWeidianId: string;
  weidianItemId: string;
  itemName: string | null;
  ownerKolCode: string | null;
  ownerKolName: string | null;
}

/** Preview of an uploaded promo sheet; confirmation re-validates before writing. */
export interface PromoSheetPreview {
  uploadId: string;
  fileName: string;
  linkRows: number;
  duplicates: number;
  toAdd: number;
  existing: number;
  conflicts: number;
  notInSheet: number;
  skipCounts: Partial<Record<PromoSheetSkipReason, number>>;
  skipSamples: PromoSheetSkipSample[];
  newItems: PromoSheetNewItem[];
  conflictsDetail: PromoSheetConflict[];
}

/** Outcome of a confirmed upload; counts reflect the moment of the write. */
export interface PromoSheetConfirmResult {
  fileName: string;
  inserted: number;
  existing: number;
  conflicts: number;
  duplicates: number;
  notInSheet: number;
  skipCounts: Partial<Record<PromoSheetSkipReason, number>>;
  conflictsDetail: PromoSheetConflict[];
}

/** Uploads a promo sheet and returns its preview. `onProgress` receives 0–100. */
export function uploadPromoSheet(
  kolId: string,
  file: File,
  onProgress?: (percent: number) => void
): Promise<PromoSheetPreview> {
  const form = new FormData();
  form.append("file", file);
  return http.request<PromoSheetPreview>({
    method: "post",
    url: `/kol/kols/${kolId}/promo-sheet-uploads`,
    // The http interceptor drops the JSON default for FormData; the browser sets the boundary.
    data: form,
    timeout: 600000,
    onUploadProgress: event => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    }
  });
}

/** Confirms a stored preview; each upload confirms once (expired is HTTP 410). */
export function confirmPromoSheet(
  kolId: string,
  uploadId: string
): Promise<PromoSheetConfirmResult> {
  return http.request<PromoSheetConfirmResult>({
    method: "post",
    url: `/kol/kols/${kolId}/promo-sheet-uploads/${uploadId}/confirm`,
    timeout: 120000
  });
}
