import { reactive } from "vue";
import { getActivePinia } from "pinia";
import { useUserStore } from "@/stores/user";
import { accessOf, type AbilitySubject } from "@/utils/permission";
import type { FinanceRole, Scenario } from "@/api/commission/finance-types";

export const financePreview =
  import.meta.env.MODE === "commission-preview" &&
  import.meta.env.VITE_COMMISSION_DEMO === "true" &&
  ["localhost", "127.0.0.1", "::1"].includes(globalThis.location?.hostname ?? "");
export const previewContext = reactive<{
  role: FinanceRole;
  scenario: Scenario;
  failWrites: boolean;
}>({
  role: "SUPER_ADMIN",
  scenario: "data",
  failWrites: false
});
export function previewSubject(role: FinanceRole): AbilitySubject {
  return {
    role:
      role === "PLATFORM_ADMIN"
        ? "PLATFORM_ADMIN"
        : role === "SUPER_ADMIN"
          ? "SUPER_ADMIN"
          : "STAFF",
    dataScope: role === "BD" ? "OWN" : "ALL",
    permissions:
      role === "PLATFORM_ADMIN" || role === "KOL"
        ? []
        : role === "BD"
          ? ["commission:view", "withdrawal:review"]
          : role === "FINANCE"
            ? ["commission:view", "withdrawal:review", "data:export"]
            : ["commission:view"]
  };
}
/** Demo authorization follows the real signed-in account; it never replaces the session. */
export function currentFinanceRole(): FinanceRole {
  if (!getActivePinia()) return previewContext.role;
  const user = useUserStore().user;
  if (!user) return "PLATFORM_ADMIN";
  const access = accessOf(user);
  if (access.isPlatformAdmin) return "PLATFORM_ADMIN";
  if (access.isSuperAdmin) return "SUPER_ADMIN";
  if (access.isOwnScope) return "BD";
  return access.can("withdrawal:review") ? "FINANCE" : "OPERATOR";
}
export function financeAbilities(subject: AbilitySubject | null) {
  const access = accessOf(subject);
  const company = !access.isPlatformAdmin;
  return {
    view: company && access.can("commission:view"),
    review: company && access.can("withdrawal:review"),
    pay: company && access.isSuperAdmin,
    export: company && access.can("data:export"),
    configure: company && access.isSuperAdmin,
    own: access.isOwnScope
  };
}
export function cents(value: string): bigint {
  if (!/^-?\d+(\.\d{1,2})?$/.test(value)) throw new Error("Invalid decimal");
  const negative = value.startsWith("-");
  const [whole, fraction = ""] = value.replace("-", "").split(".");
  const amount = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
  return negative ? -amount : amount;
}
export function decimal(value: bigint): string {
  const magnitude = value < 0n ? -value : value;
  return `${value < 0n ? "-" : ""}${magnitude / 100n}.${String(magnitude % 100n).padStart(2, "0")}`;
}
export function sumMoney(values: readonly string[]): string {
  return decimal(values.reduce((total, value) => total + cents(value), 0n));
}
export function validMoney(value: string, positive = true): boolean {
  return /^\d+(\.\d{1,2})?$/.test(value) && (!positive || cents(value) > 0n);
}

export function periodBounds(dates: readonly [string, string] | null): {
  start?: string;
  end?: string;
} {
  if (!dates) return {};
  const start = new Date(`${dates[0]}T00:00:00`);
  const end = new Date(`${dates[1]}T00:00:00`);
  end.setDate(end.getDate() + 1);
  return { start: start.toISOString(), end: end.toISOString() };
}

/** Keep the simulated transport at least as restrictive as the real page capabilities. */
export function financePreviewAllows(
  capability: "view" | "review" | "pay" | "export" | "configure"
): boolean {
  const subject = getActivePinia() ? useUserStore().user : previewSubject(previewContext.role);
  return financeAbilities(subject)[capability];
}
