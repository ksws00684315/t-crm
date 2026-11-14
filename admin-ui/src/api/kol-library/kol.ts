import type { PageResult } from "../types";
import { http } from "../http";

/**
 * Creator library API (ADR-038, task 143). Field names mirror the backend records; amounts and
 * ratios are strings with two decimals, ids are strings, dates are `yyyy-MM-dd`, instants are ISO
 * 8601 UTC.
 */

/** Platform values of the library; the names live in the language pack (`library.platform.*`). */
export type LibraryPlatform = "TIKTOK" | "INSTAGRAM" | "YOUTUBE" | "OTHER";
export type LibraryCountry =
  | "US"
  | "GB"
  | "MX"
  | "DE"
  | "FR"
  | "IT"
  | "ES"
  | "JP"
  | "ID"
  | "TH"
  | "VN"
  | "MY"
  | "PH"
  | "SG"
  | "OTHER";
export type LibraryCategory =
  | "BEAUTY_PERSONAL_CARE"
  | "HEALTH_SUPPLEMENTS"
  | "HOME_DAILY"
  | "ELECTRONICS_3C"
  | "FASHION_ACCESSORIES"
  | "MATERNAL_BABY"
  | "FOOD_BEVERAGE"
  | "PET"
  | "SPORTS_OUTDOOR"
  | "OTHER";
export type LibraryLevel = "HIGH" | "MEDIUM" | "LOW";
export type LibraryGrade = "S" | "A" | "B" | "C";
export type LibraryOutreachStatus =
  "NOT_CONTACTED" | "EMAIL_SENT" | "REPLIED" | "WHATSAPP_ADDED" | "REJECTED" | "COOPERATING";
export type LibrarySampleStatus = "NOT_SENT" | "SHIPPED" | "RECEIVED" | "NOT_NEEDED";
export type LibraryContentForm = "SHORT_VIDEO" | "LIVE" | "POST";
export type LibrarySampleType = "GIFT" | "PHOTO_SHOOT";
export type LibraryAudienceAge =
  "AGE_18_24" | "AGE_25_34" | "AGE_35_44" | "AGE_45_54" | "AGE_55_PLUS";
export type LibraryAudienceGender = "MOSTLY_FEMALE" | "MOSTLY_MALE" | "BALANCED";

/** `LibraryKolQuery`: omitted fields mean "no filter". */
export interface LibraryKolQuery {
  page?: number;
  size?: number;
  keyword?: string;
  platform?: LibraryPlatform;
  countryCode?: LibraryCountry;
  category?: LibraryCategory;
  outreachStatus?: LibraryOutreachStatus;
  followUpOwner?: string;
}

/** A list row: the data of the eight list columns (requirement 1.2.5 ②, 2026-11-10). */
export interface LibraryKolVO {
  id: string;
  platform: LibraryPlatform;
  kolUsername: string;
  displayName: string;
  followers: string | null;
  countryCode: LibraryCountry;
  categories: LibraryCategory[];
  grade: LibraryGrade | null;
  outreachStatus: LibraryOutreachStatus;
  followUpOwner: string;
}

/** Creator totals of the detail page (requirement 1.2.6 ③); `roi` is null when cost is 0. */
export interface LibraryTotals {
  contentCount: number;
  clicks: number;
  orders: number;
  gmv: string;
  totalCost: string;
  roi: string | null;
}

/** Full profile and totals of the detail page. */
export interface LibraryKolDetailVO {
  id: string;
  platform: LibraryPlatform;
  kolUsername: string;
  displayName: string;
  followers: string | null;
  countryCode: LibraryCountry;
  profileUrl: string | null;
  categories: LibraryCategory[];
  avgViews: string | null;
  engagementRate: string | null;
  audienceCountry: string | null;
  audienceAgeRange: LibraryAudienceAge | null;
  audienceGender: LibraryAudienceGender | null;
  contact: string | null;
  contentQuality: LibraryLevel | null;
  salesPotential: LibraryLevel | null;
  cooperationAttitude: LibraryLevel | null;
  grade: LibraryGrade | null;
  tags: string[];
  remark: string | null;
  outreachStatus: LibraryOutreachStatus;
  followUpOwner: string;
  lastContactDate: string | null;
  sampleStatus: LibrarySampleStatus;
  trackingNo: string | null;
  sampleDate: string | null;
  followUpRemark: string | null;
  createTime: string;
  updateTime: string;
  totals: LibraryTotals;
}

/** `LibraryKolRequest`: the whole profile; numbers may travel as strings (ids and amounts). */
export type LibraryKolRequest = Omit<
  LibraryKolDetailVO,
  "id" | "createTime" | "updateTime" | "totals"
>;

/** `LibraryFollowUpRequest`: the six follow-up fields only (requirement 1.2.5 ⑤). */
export interface LibraryFollowUpRequest {
  outreachStatus: LibraryOutreachStatus;
  lastContactDate: string | null;
  sampleStatus: LibrarySampleStatus;
  trackingNo: string | null;
  sampleDate: string | null;
  followUpRemark: string | null;
}

/** One content record with its calculated fields (requirement 1.2.6 ③). */
export interface LibraryContentVO {
  id: string;
  publishUrl: string;
  publishDate: string;
  contentForm: LibraryContentForm;
  clicks: string | null;
  orders: string | null;
  gmv: string | null;
  sampleType: LibrarySampleType | null;
  sampleCost: string | null;
  slotFee: string | null;
  commissionRate: string | null;
  estimatedCommission: string;
  totalCost: string;
  actualRoi: string | null;
  nextAction: string | null;
  updateTime: string;
}

/** The manual fields of one content record; the calculated three are never sent. */
export type LibraryContentRequest = Omit<
  LibraryContentVO,
  "id" | "updateTime" | "estimatedCommission" | "totalCost" | "actualRoi"
>;

/** `data` of a 409 KOL_LIBRARY_DUPLICATE answer: who entered the creator first. */
export interface LibraryDuplicate {
  id: string;
  followUpOwner: string;
  displayName: string;
}

/** Paged creator list, most recently changed first. */
export function listLibraryKols(query: LibraryKolQuery): Promise<PageResult<LibraryKolVO>> {
  return http.request<PageResult<LibraryKolVO>>({
    method: "get",
    url: "/kol-library/kols",
    params: query
  });
}

export function getLibraryKol(id: string): Promise<LibraryKolDetailVO> {
  return http.request<LibraryKolDetailVO>({ method: "get", url: `/kol-library/kols/${id}` });
}

export function createLibraryKol(request: LibraryKolRequest): Promise<LibraryKolDetailVO> {
  return http.request<LibraryKolDetailVO>({
    method: "post",
    url: "/kol-library/kols",
    data: request
  });
}

export interface LibraryWorkbookImportResult {
  created: number;
  errors: { row: number; errorCode: string; column: string | null }[];
}

export function downloadLibraryWorkbookTemplate() {
  return http.download({ method: "get", url: "/kol-library/kols/imports/template" });
}

export function importLibraryWorkbook(file: File): Promise<LibraryWorkbookImportResult> {
  const data = new FormData();
  data.append("file", file);
  return http.request({
    method: "post",
    url: "/kol-library/kols/imports",
    timeout: 90_000,
    data
  });
}

export function updateLibraryKol(
  id: string,
  request: LibraryKolRequest
): Promise<LibraryKolDetailVO> {
  return http.request<LibraryKolDetailVO>({
    method: "put",
    url: `/kol-library/kols/${id}`,
    data: request
  });
}

/** Updates the six follow-up fields only; the other groups are left as they are. */
export function updateLibraryFollowUp(
  id: string,
  request: LibraryFollowUpRequest
): Promise<LibraryKolDetailVO> {
  return http.request<LibraryKolDetailVO>({
    method: "put",
    url: `/kol-library/kols/${id}/follow-up`,
    data: request
  });
}

/** Deletes the creator and its content records; super administrators only. */
export function deleteLibraryKol(id: string): Promise<null> {
  return http.request<null>({ method: "delete", url: `/kol-library/kols/${id}` });
}

export function listLibraryContents(kolId: string): Promise<LibraryContentVO[]> {
  return http.request<LibraryContentVO[]>({
    method: "get",
    url: `/kol-library/kols/${kolId}/contents`
  });
}

export function createLibraryContent(
  kolId: string,
  request: LibraryContentRequest
): Promise<LibraryContentVO> {
  return http.request<LibraryContentVO>({
    method: "post",
    url: `/kol-library/kols/${kolId}/contents`,
    data: request
  });
}

export function updateLibraryContent(
  kolId: string,
  contentId: string,
  request: LibraryContentRequest
): Promise<LibraryContentVO> {
  return http.request<LibraryContentVO>({
    method: "put",
    url: `/kol-library/kols/${kolId}/contents/${contentId}`,
    data: request
  });
}

/** Deletes one content record; super administrators only. */
export function deleteLibraryContent(kolId: string, contentId: string): Promise<null> {
  return http.request<null>({
    method: "delete",
    url: `/kol-library/kols/${kolId}/contents/${contentId}`
  });
}
