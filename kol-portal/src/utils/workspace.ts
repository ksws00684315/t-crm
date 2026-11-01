import type { FundSummary } from "@/api/creator";
import { centsMoney, moneyCents } from "@/utils/money";

/**
 * Workspace figures derived from the five-layer commission overview (task 92, ADR-032 appendix B).
 * Everything is integer-cent arithmetic on the strings the server sent, so nothing drifts and no
 * figure is rounded for display.
 *
 * - commission income = estimated − deducted − pendingDeduction (negative when the commission
 *   cannot cover the advances);
 * - total income = commission income + advances received;
 * - settled commission = available + deducted: the released money before advance deductions, not
 *   yet requested for withdrawal. `pendingDeduction` is deliberately not subtracted (owner
 *   decision 2026-10-31);
 * - the four commission cards (settled, frozen, withdrawing, withdrawn) add up to `estimated`.
 */
export interface WorkspaceFigures {
  totalIncome: string;
  commissionIncome: string;
  prepayPaid: string;
  available: string;
  estimatedCommission: string;
  settled: string;
  frozen: string;
  occupied: string;
  commissionPaid: string;
  /** Sum of the four commission cards; equals `estimatedCommission` when the server is consistent. */
  cardsTotal: string;
}

export function workspaceFigures(summary: FundSummary): WorkspaceFigures {
  const estimated = moneyCents(summary.estimatedCommissionCny);
  const deducted = moneyCents(summary.prepayDeductionCny);
  const pending = moneyCents(summary.prepayOutstandingCny);
  const prepay = moneyCents(summary.receivedPrepayCny);
  const available = moneyCents(summary.availableCny);
  const frozen = moneyCents(summary.frozenCny);
  const occupied = moneyCents(summary.withdrawingCny);
  const commissionPaid = moneyCents(summary.withdrawnCny);
  const commissionIncome = estimated - deducted - pending;
  const settled = available + deducted;
  return {
    totalIncome: centsMoney(commissionIncome + prepay),
    commissionIncome: centsMoney(commissionIncome),
    prepayPaid: centsMoney(prepay),
    available: centsMoney(available),
    estimatedCommission: centsMoney(estimated),
    settled: centsMoney(settled),
    frozen: centsMoney(frozen),
    occupied: centsMoney(occupied),
    commissionPaid: centsMoney(commissionPaid),
    cardsTotal: centsMoney(settled + frozen + occupied + commissionPaid)
  };
}

/** True for a negative decimal string such as "-0.50". */
export function isNegativeAmount(value: string): boolean {
  return moneyCents(value) < 0n;
}

/** Percentage text of the profile's commission rate ("12" -> "12%"); null when not configured. */
export function rateLabel(rate: string | null | undefined): string | null {
  return rate == null || rate === "" ? null : `${rate}%`;
}
