/**
 * Codes for the cancel reasons the backend stores verbatim in `crm_kol_bd_transfer.cancel_reason`
 * (design §4.13). The records page shows `bdTransfer.cancelReasons.<code>` from the language pack;
 * a reason this build does not know is shown as stored. The two codes shared with the in-app
 * notifications (task 20) keep the same names.
 */
export type BdTransferCancelCode =
  | "OWNERSHIP_CHANGED"
  | "TARGET_UNAVAILABLE"
  | "TARGET_DISABLED"
  | "REPLACED_BY_IMMEDIATE"
  | "MANUAL";

const CANCEL_CODES: ReadonlyMap<string, BdTransferCancelCode> = new Map([
  ["执行时归属已变化", "OWNERSHIP_CHANGED"],
  ["目标商务不可用", "TARGET_UNAVAILABLE"],
  ["目标商务已停用", "TARGET_DISABLED"],
  ["被立即转移取代", "REPLACED_BY_IMMEDIATE"],
  ["手动取消", "MANUAL"]
]);

/** The code for a stored cancel reason, or null when it is not one the build knows. */
export function bdTransferCancelCode(
  reason: string | null | undefined
): BdTransferCancelCode | null {
  if (!reason) return null;
  return CANCEL_CODES.get(reason.trim()) ?? null;
}
