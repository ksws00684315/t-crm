import { translate } from "@/locales";
import type {
  LibraryAudienceAge,
  LibraryAudienceGender,
  LibraryCategory,
  LibraryContentForm,
  LibraryCooperationStage,
  LibraryCountry,
  LibraryGrade,
  LibraryLevel,
  LibraryOutreachStatus,
  LibraryPlatform,
  LibrarySampleStatus,
  LibrarySampleType
} from "@/api/kol-library/kol";

/**
 * Option lists and labels of the creator library (requirement 1.2.6 ①). The values are the English
 * constants the API stores; every displayed name comes from the language pack under
 * `library.<option>.<VALUE>`, so the customer can review the wording in one place (requirement Q-2)
 * and one new value needs no code change beyond this list.
 */

/** `defaultValue` marks the first entry of a dropdown that has a default (requirement 1.2.6 ①). */
export interface LibraryOption<E extends string> {
  value: E;
  defaultValue?: boolean;
}

function options<E extends string>(values: readonly E[], defaultValue?: E): LibraryOption<E>[] {
  return values.map(value => ({
    value,
    ...(value === defaultValue ? { defaultValue: true } : {})
  }));
}

export const LIBRARY_PLATFORMS = options<LibraryPlatform>(
  ["TIKTOK", "INSTAGRAM", "YOUTUBE", "OTHER"],
  "TIKTOK"
);
export const LIBRARY_COUNTRIES = options<LibraryCountry>(
  ["US", "GB", "MX", "DE", "FR", "IT", "ES", "JP", "ID", "TH", "VN", "MY", "PH", "SG", "OTHER"],
  "US"
);
export const LIBRARY_CATEGORIES = options<LibraryCategory>([
  "BEAUTY_PERSONAL_CARE",
  "HEALTH_SUPPLEMENTS",
  "HOME_DAILY",
  "ELECTRONICS_3C",
  "FASHION_ACCESSORIES",
  "MATERNAL_BABY",
  "FOOD_BEVERAGE",
  "PET",
  "SPORTS_OUTDOOR",
  "OTHER"
]);
export const LIBRARY_LEVELS = options<LibraryLevel>(["HIGH", "MEDIUM", "LOW"]);
export const LIBRARY_GRADES = options<LibraryGrade>(["S", "A", "B", "C"]);
export const LIBRARY_COOPERATION_STAGES = options<LibraryCooperationStage>([
  "FIRST_COOPERATION",
  "REPEAT_INVESTMENT",
  "LONG_TERM_PARTNERSHIP",
  "EXCLUSIVE"
]);
export const LIBRARY_OUTREACH_STATUSES = options<LibraryOutreachStatus>(
  ["NOT_CONTACTED", "EMAIL_SENT", "REPLIED", "WHATSAPP_ADDED", "REJECTED", "COOPERATING"],
  "NOT_CONTACTED"
);
export const LIBRARY_SAMPLE_STATUSES = options<LibrarySampleStatus>(
  ["NOT_SENT", "SHIPPED", "RECEIVED", "NOT_NEEDED"],
  "NOT_SENT"
);
export const LIBRARY_CONTENT_FORMS = options<LibraryContentForm>(["SHORT_VIDEO", "LIVE", "POST"]);
export const LIBRARY_SAMPLE_TYPES = options<LibrarySampleType>(["GIFT", "PHOTO_SHOOT"]);
export const LIBRARY_AUDIENCE_AGES = options<LibraryAudienceAge>([
  "AGE_18_24",
  "AGE_25_34",
  "AGE_35_44",
  "AGE_45_54",
  "AGE_55_PLUS"
]);
export const LIBRARY_AUDIENCE_GENDERS = options<LibraryAudienceGender>([
  "MOSTLY_FEMALE",
  "MOSTLY_MALE",
  "BALANCED"
]);

/** Option key in the language pack for one value of the given option family. */
export type LibraryOptionFamily =
  | "platform"
  | "country"
  | "category"
  | "level"
  | "grade"
  | "cooperationStage"
  | "outreachStatus"
  | "sampleStatus"
  | "contentForm"
  | "sampleType"
  | "audienceAge"
  | "audienceGender";

/** Displayed name of one library value; an unknown value is shown as itself. */
export function libraryOptionLabel(family: LibraryOptionFamily, value: string | null): string {
  if (!value) return "-";
  const key = `library.${family}.${value}`;
  const text = translate(key);
  return text === key ? value : text;
}

/** The value a dropdown starts with: the marked default, else the first entry. */
export function libraryDefaultValue<E extends string>(list: readonly LibraryOption<E>[]): E {
  return (list.find(item => item.defaultValue) ?? list[0]).value;
}

/** Colour of an outreach status tag in the list. */
export function outreachTagType(
  status: LibraryOutreachStatus | null
): "success" | "primary" | "warning" | "info" | "danger" {
  switch (status) {
    case "COOPERATING":
      return "success";
    case "REPLIED":
    case "WHATSAPP_ADDED":
      return "primary";
    case "EMAIL_SENT":
      return "warning";
    case "REJECTED":
      return "danger";
    default:
      return "info";
  }
}

/** Colour of a sample status tag on the detail page. */
export function sampleTagType(
  status: LibrarySampleStatus | null
): "success" | "primary" | "warning" | "info" {
  switch (status) {
    case "RECEIVED":
      return "success";
    case "SHIPPED":
      return "primary";
    case "NOT_NEEDED":
      return "warning";
    default:
      return "info";
  }
}
