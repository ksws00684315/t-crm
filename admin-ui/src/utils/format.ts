/** Formats an ISO 8601 instant for display in the operator's local time zone. */
export function formatInstant(value: string | null | undefined): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(date);
}

/** Display an instant through minute precision, as requested for creator invitations. */
export function formatInstantToMinute(value: string | null | undefined): string {
  const full = formatInstant(value);
  return full === "-" ? full : full.slice(0, -3);
}

const CURRENCY_SYMBOLS: Record<string, string> = { CNY: "¥", USD: "$", EUR: "€", GBP: "£" };

/** Display price with its currency: "¥12.50", "$60.00", or "12.50 JPY" without a known symbol. */
export function formatPrice(
  price: string | null | undefined,
  currency: string | null | undefined
): string {
  if (price === null || price === undefined || price === "") return "-";
  const code = currency || "CNY";
  const symbol = CURRENCY_SYMBOLS[code];
  return symbol ? `${symbol}${price}` : `${price} ${code}`;
}
