import { describe, expect, it } from "vitest";
import {
  canSwitchCompany,
  companiesOf,
  isMultiCompany,
  shownCompanyId,
  singleFlight
} from "../company";

const companies = [
  { tenantId: "1", tenantName: "A", role: "SUPER_ADMIN" as const },
  { tenantId: "2", tenantName: "B", role: "STAFF" as const }
];

describe("company helpers", () => {
  it("uses the shown company for X-Company-Id, never for the platform administrator", () => {
    expect(shownCompanyId({ role: "STAFF", tenantId: "2" })).toBe("2");
    expect(shownCompanyId({ role: "PLATFORM_ADMIN", tenantId: "2" })).toBeNull();
    expect(shownCompanyId({ role: "STAFF", tenantId: null })).toBeNull();
    expect(shownCompanyId(null)).toBeNull();
  });

  it("offers switching only with more than one company and never to the platform administrator", () => {
    expect(canSwitchCompany({ role: "SUPER_ADMIN", companies })).toBe(true);
    expect(canSwitchCompany({ role: "SUPER_ADMIN", companies: companies.slice(0, 1) })).toBe(false);
    expect(canSwitchCompany({ role: "SUPER_ADMIN" })).toBe(false);
    expect(companiesOf({ role: "PLATFORM_ADMIN", companies })).toEqual([]);
  });

  it("marks rows that belong to more than one company", () => {
    expect(isMultiCompany({ companyCount: 2 })).toBe(true);
    expect(isMultiCompany({ companyCount: 1 })).toBe(false);
    expect(isMultiCompany({})).toBe(false);
  });

  it("runs a burst of calls once and allows a later run", async () => {
    let runs = 0;
    let release: () => void = () => {};
    const task = singleFlight(
      () =>
        new Promise<void>(resolve => {
          runs += 1;
          release = resolve;
        })
    );
    const first = task();
    const second = task();
    expect(runs).toBe(1);
    release();
    await Promise.all([first, second]);
    const third = task();
    expect(runs).toBe(2);
    release();
    await third;
  });
});
