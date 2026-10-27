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
