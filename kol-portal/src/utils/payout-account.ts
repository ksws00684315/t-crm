/** USDT networks the backend accepts (design §4.11); used by the form and its tests. */
export const USDT_NETWORKS = ["TRC20", "ERC20", "BEP20", "SOL", "TON"] as const;
export type UsdtNetwork = (typeof USDT_NETWORKS)[number];

/** A wallet address is letters and digits only, 3–128 characters (backend `@Pattern` + `@Size`). */
export const ALNUM_ACCOUNT = /^[0-9A-Za-z]{3,128}$/;

/** Outcome of the QR pre-check; the backend still validates the file header and size. */
export type QrCheck = "ok" | "type" | "size";

export const QR_MAX_BYTES = 2 * 1024 * 1024;

/** Pre-checks a picked QR image by MIME type and size before it is uploaded (task 15b §4.1.3). */
export function checkQrFile(file: File): QrCheck {
  const type = file.type === "" ? extensionType(file.name) : file.type;
  if (type !== "image/png" && type !== "image/jpeg") return "type";
  if (file.size > QR_MAX_BYTES) return "size";
  return "ok";
}

function extensionType(name: string): string {
  if (/\.png$/i.test(name)) return "image/png";
  if (/\.jpe?g$/i.test(name)) return "image/jpeg";
  return "";
}
