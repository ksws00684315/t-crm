import type { CompanyVO, Role } from "@/api/types";

/** BroadcastChannel that tells the other tabs of this browser about a company switch. */
export const COMPANY_CHANNEL = "tcrm-admin-company";

/** Message posted on {@link COMPANY_CHANNEL}. Receivers re-read `/auth/me`; the id is informative. */
export interface CompanySwitchedMessage {
  type: "switched";
  tenantId: string;
}

interface CompanySubject {
  role: Role;
  tenantId?: string | null;
  companies?: CompanyVO[] | null;
}

/**
 * The company this tab shows, sent as X-Company-Id. Null for the platform administrator and when
 * there is no account. It is only compared by the backend, never used to pick a company.
 */
export function shownCompanyId(user: CompanySubject | null | undefined): string | null {
  if (!user || user.role === "PLATFORM_ADMIN") return null;
  return user.tenantId || null;
}

/** Companies of the account; always empty for the platform administrator. */
export function companiesOf(user: CompanySubject | null | undefined): CompanyVO[] {
  if (!user || user.role === "PLATFORM_ADMIN") return [];
  return user.companies ?? [];
}

/** The header offers a company menu only when there is somewhere else to go. */
export function canSwitchCompany(user: CompanySubject | null | undefined): boolean {
  return companiesOf(user).length > 1;
}

/** A row of the company user list that also belongs to other companies (ADR-022). */
export function isMultiCompany(row: { companyCount?: number } | null | undefined): boolean {
  return (row?.companyCount ?? 1) > 1;
}

/**
 * Wraps `task` so that calls arriving while it runs share that run. Several requests failing with
 * 409 AUTH_COMPANY_SWITCHED at the same time therefore warn and refresh once.
 */
export function singleFlight(task: () => Promise<void>): () => Promise<void> {
  let running: Promise<void> | null = null;
  return () => {
    running ??= task().finally(() => {
      running = null;
    });
    return running;
  };
}
