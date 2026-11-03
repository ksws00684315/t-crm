/**
 * Money helpers on integer cents (#133 item 3): parsing keeps the sign of values like "-0.50"
 * (whose whole part alone would round to zero) and formatting renders negative cents as "-0.50"
 * instead of the broken "0.-50" that BigInt division and remainder produced.
 */
export function moneyCents(value: string): bigint {
  const negative = value.startsWith("-");
  const unsigned = negative ? value.slice(1) : value;
  const [whole, fraction = ""] = unsigned.split(".");
  const cents = BigInt(whole || "0") * 100n + BigInt((fraction + "00").slice(0, 2));
  return negative ? -cents : cents;
}

export function centsMoney(value: bigint): string {
  const negative = value < 0n;
  const amount = negative ? -value : value;
  const text = `${amount / 100n}.${(amount % 100n).toString().padStart(2, "0")}`;
  return negative ? `-${text}` : text;
}

const CURRENCY_SYMBOLS: Record<string, string> = { CNY: "¥", USD: "$", EUR: "€", GBP: "£" };
const AMOUNT_PATTERN = /^-?\d+(?:\.\d+)?$/;

/**
 * Symbols the currency and groups the decimal string without converting it to a float (规范 13
 * §7). Fraction digits are kept exactly as received; a currency without a symbol uses
 * "1,234 JPY" so it cannot be confused with CNY.
 */
export function formatMoney(amount: string | null | undefined, currency?: string | null): string {
  if (amount == null) return "-";
  const raw = amount.trim();
  if (!AMOUNT_PATTERN.test(raw)) return "-";
  const negative = raw.startsWith("-");
  const unsigned = negative ? raw.slice(1) : raw;
  const [whole, fraction = ""] = unsigned.split(".");
  const normalizedWhole = whole.replace(/^0+(?=\d)/, "");
  const grouped = normalizedWhole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const number = fraction ? `${grouped}.${fraction}` : grouped;
  const code = (currency?.trim() || "CNY").toUpperCase();
  const symbol = CURRENCY_SYMBOLS[code];
  const body = symbol ? `${symbol}${number}` : `${number} ${code}`;
  return negative ? `-${body}` : body;
}

/**
 * Exact decimal sum for amounts the server sends as strings. It scales every value to the widest
 * fraction length and adds BigInts, so display totals never pick up float drift.
 */
export function sumDecimals(values: (string | null | undefined)[]): string | null {
  const parts = values
    .filter((value): value is string => value != null && AMOUNT_PATTERN.test(value.trim()))
    .map(value => value.trim());
  if (parts.length === 0) return null;
  const scale = Math.max(...parts.map(value => (value.split(".")[1] ?? "").length));
  const factor = 10n ** BigInt(scale);
  let total = 0n;
  for (const value of parts) {
    const negative = value.startsWith("-");
    const unsigned = negative ? value.slice(1) : value;
    const [whole, fraction = ""] = unsigned.split(".");
    const scaled = BigInt(whole) * factor + BigInt((fraction + "0".repeat(scale)).slice(0, scale));
    total += negative ? -scaled : scaled;
  }
  if (scale === 0) return total.toString();
  const negative = total < 0n;
  const digits = (negative ? -total : total).toString().padStart(scale + 1, "0");
  const whole = digits.slice(0, -scale);
  const fraction = digits.slice(-scale);
  return `${negative ? "-" : ""}${whole}.${fraction}`;
}
