import { describe, expect, it } from "vitest";
import { canCancelPrepay, prepayAbilities, sameCny, validCny, validPrepayFiles } from "../prepay";
import type { AbilitySubject } from "../permission";
import type { PrepayRow } from "@/api/commission/prepay";
const staff = (permissions: string[]): AbilitySubject & { id: string } => ({
  id: "7",
  role: "STAFF",
  dataScope: "OWN",
  postTemplate: "BD",
  status: 1,
  permissions
});
describe("prepay business boundaries", () => {
  it("keeps pay authority independent from review", () => {
    expect(prepayAbilities(staff(["prepay:pay"]))).toEqual({
      view: true,
      apply: false,
      review: false,
      operationsReview: false,
      financeReview: false,
      pay: true,
      export: false
    });
    expect(prepayAbilities(staff(["prepay:review"])).pay).toBe(false);
    expect(prepayAbilities({ ...staff(["prepay:pay"]), role: "PLATFORM_ADMIN" }).pay).toBe(false);
    expect(prepayAbilities(null).view).toBe(false);
  });
  it("restricts cancellation to the pending applicant", () => {
    const row = { applicantId: "7", status: "WAITING_DETAILS" } as unknown as PrepayRow;
    expect(canCancelPrepay(staff(["prepay:apply"]), row)).toBe(true);
    expect(canCancelPrepay(staff(["prepay:pay"]), row)).toBe(false);
    expect(canCancelPrepay({ ...staff(["prepay:apply"]), id: "8" }, row)).toBe(false);
    expect(canCancelPrepay(staff(["prepay:apply"]), { ...row, status: "APPROVED" })).toBe(false);
  });
  it("keeps finance review separate from operations review and payment", () => {
    expect(prepayAbilities(staff(["prepay:finance-review"]))).toMatchObject({
      view: true,
      operationsReview: false,
      financeReview: true,
      pay: false
    });
  });
  it("validates decimal strings without rounding", () => {
    for (const value of ["0.01", "999999999999.99", "1"]) expect(validCny(value)).toBe(true);
    for (const value of ["0", "0.00", "-1", "01", "1.001", "1e2", "1000000000000", " 1"])
      expect(validCny(value)).toBe(false);
    expect(sameCny("999999999999.9", "999999999999.90")).toBe(true);
    expect(sameCny("999999999999.90", "999999999999.91")).toBe(false);
  });
  it("enforces file count MIME and size", () => {
    const pdf = new File(["test"], "proof.pdf", { type: "application/pdf" });
    expect(validPrepayFiles(Array(5).fill(pdf))).toBe(true);
    expect(validPrepayFiles(Array(6).fill(pdf))).toBe(false);
    expect(validPrepayFiles([new File([], "empty.pdf", { type: "application/pdf" })])).toBe(false);
    expect(validPrepayFiles([new File(["x"], "fake.pdf", { type: "text/plain" })])).toBe(false);
    Object.defineProperty(pdf, "size", { value: 10 * 1024 * 1024, configurable: true });
    expect(validPrepayFiles([pdf], 1)).toBe(true);
    Object.defineProperty(pdf, "size", { value: 10 * 1024 * 1024 + 1 });
    expect(validPrepayFiles([pdf], 1)).toBe(false);
  });
});
