/*
 * Commission rates: the API carries a fraction with 4 decimals ("0.1550"), people type a
 * percentage with at most 2 decimals ("15.5"). String arithmetic avoids floating point drift.
 */

const PERCENT_PATTERN = /^(\d{1,3})(?:\.(\d{1,2}))?$/;
const RATE_PATTERN = /^(\d)(?:\.(\d{1,4}))?$/;

/** Validates a percentage input; empty is allowed (no default rate). */
export function validatePercent(value: string | null | undefined): string | null {
  const text = (value ?? "").trim();
  if (text === "") return null;
  return percentToRate(text) === null ? "请输入 0～100 的数字，最多 2 位小数" : null;
}

/** "15.5" → "0.1550"; empty → null; invalid or above 100 → null. */
export function percentToRate(value: string | null | undefined): string | null {
  const match = PERCENT_PATTERN.exec((value ?? "").trim());
  if (!match) return null;
  const whole = Number(match[1]);
  const fraction = (match[2] ?? "").padEnd(2, "0");
  if (whole > 100 || (whole === 100 && fraction !== "00")) return null;
  // percent * 100 as an integer number of basis points, then shift by 4 decimals.
  const basisPoints = whole * 100 + Number(fraction);
  const text = String(basisPoints).padStart(5, "0");
  return `${text.slice(0, -4)}.${text.slice(-4)}`;
}

/** "0.1550" → "15.5"; "1.0000" → "100"; null or malformed → "". */
export function rateToPercent(rate: string | null | undefined): string {
  const match = RATE_PATTERN.exec((rate ?? "").trim());
  if (!match) return "";
  const digits = match[1] + (match[2] ?? "").padEnd(4, "0");
  const basisPoints = Number(digits);
  const whole = Math.trunc(basisPoints / 100);
  const fraction = String(basisPoints % 100)
    .padStart(2, "0")
    .replace(/0+$/, "");
  return fraction ? `${whole}.${fraction}` : String(whole);
}

/** Display text: "15.5%", or "-" when not set. */
export function formatRate(rate: string | null | undefined): string {
  const percent = rateToPercent(rate);
  return percent === "" ? "-" : `${percent}%`;
}
