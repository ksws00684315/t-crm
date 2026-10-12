/** Local calendar date. Watermark text must not use the UTC date from toISOString. */
export function watermarkText(nickname: string, username: string, now: Date): string {
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${nickname} · ${username} · ${now.getFullYear()}-${month}-${day}`;
}

/** Canvas fill from a #rrggbb token. Very low alpha so the text stays readable. */
export function watermarkInk(hex: string, alpha = 0.08): string {
  const match = /^#?([0-9a-fA-F]{6})$/.exec(hex.trim());
  if (!match) return `rgba(21, 24, 29, ${alpha})`;
  const value = Number.parseInt(match[1], 16);
  const red = (value >> 16) & 255;
  const green = (value >> 8) & 255;
  const blue = value & 255;
  return `rgba(${red}, ${green}, ${blue}, ${alpha})`;
}
