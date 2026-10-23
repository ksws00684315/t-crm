import { reactive } from "vue";
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
  role: "KOL",
  scenario: "data",
  failWrites: false
});
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
