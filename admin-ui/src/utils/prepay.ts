import type { PrepayRow } from "@/api/commission/prepay";
import { accessOf, type AbilitySubject } from "./permission";

export const PREPAY_STATUSES = [
  "WAITING_DETAILS",
  "PENDING_OPERATIONS_REVIEW",
  "PENDING_FINANCE_REVIEW",
  "APPROVED",
  "PAID",
  "REJECTED",
  "CANCELLED"
] as const;
export function validCny(value: string): boolean {
  return /^(0|[1-9]\d{0,11})(\.\d{1,2})?$/.test(value) && /[1-9]/.test(value);
}
export function sameCny(left: string, right: string): boolean {
  const canonical = (value: string) => {
    const [whole, fraction = ""] = value.split(".");
    return `${whole}.${fraction.padEnd(2, "0")}`;
  };
  return canonical(left) === canonical(right);
}
export function validPrepayFiles(files: readonly File[], maxCount = 5): boolean {
  return (
    files.length <= maxCount &&
    files.every(
      file =>
        ["image/png", "image/jpeg", "application/pdf"].includes(file.type) &&
        file.size > 0 &&
        file.size <= 10 * 1024 * 1024
    )
  );
}
export function prepayAbilities(user: AbilitySubject | null | undefined) {
  const access = accessOf(user);
  const company = !!user && !access.isPlatformAdmin;
  const view =
    company &&
    ["commission:view", "prepay:review", "prepay:finance-review", "prepay:pay"].some(code =>
      access.can(
        code as "commission:view" | "prepay:review" | "prepay:finance-review" | "prepay:pay"
      )
    );
  return {
    view,
    apply: company && access.can("prepay:apply"),
    review: company && access.can("prepay:review"),
    operationsReview: company && access.can("prepay:review"),
    financeReview: company && access.can("prepay:finance-review"),
    pay: company && access.can("prepay:pay"),
    export: view && access.can("data:export")
  };
}
export function canCancelPrepay(
  user: (AbilitySubject & { id: string }) | null | undefined,
  row: PrepayRow
): boolean {
  return (
    prepayAbilities(user).apply && user?.id === row.applicantId && row.status === "WAITING_DETAILS"
  );
}
