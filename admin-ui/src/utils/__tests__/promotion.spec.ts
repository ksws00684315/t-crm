import { describe, expect, it } from "vitest";
import { looksLikeKolCode, validateKolCode } from "../kol-validation";
import {
  DISPLAY_RELATION_STATUSES,
  incompletePromotionFields,
  missingPromotionFields,
  promotionFieldDiffs,
  relationTagType,
  RELATION_STATUS_OPTIONS
} from "../promotion";

describe("creator code validation (ADR-023 §1)", () => {
  it("accepts the new format and rejects the old and malformed values", () => {
    expect(validateKolCode("KOL-ASD001")).toBeNull();
    expect(validateKolCode("KOL-QWE1234")).toBeNull();
    expect(validateKolCode("KOL-ABC12345678")).toBeNull();
    expect(validateKolCode("KOL0001")).not.toBeNull();
    expect(validateKolCode("KOL-asd001")).not.toBeNull();
    expect(validateKolCode("KOL-AB001")).not.toBeNull();
    expect(validateKolCode("KOL-ABC12")).not.toBeNull();
    expect(validateKolCode("KOL-ABC123456789")).not.toBeNull();
    expect(validateKolCode("  ")).not.toBeNull();
  });

  it("recognizes code-shaped input so a claim search can pick the right check", () => {
    expect(looksLikeKolCode("KOL-ASD001")).toBe(true);
    expect(looksLikeKolCode("kol-asd001")).toBe(true);
    expect(looksLikeKolCode("KOL0001")).toBe(true);
    expect(looksLikeKolCode("ada@example.com")).toBe(false);
  });
});

describe("relation status helpers", () => {
  it("lists the three editable states and adds pending update for display", () => {
    expect(RELATION_STATUS_OPTIONS).toEqual(["PENDING_PROMOTION", "PROMOTING", "INVALID"]);
    expect(DISPLAY_RELATION_STATUSES).toEqual([
      "PENDING_PROMOTION",
      "PROMOTING",
      "INVALID",
      "PENDING_UPDATE"
    ]);
    expect(new Set(DISPLAY_RELATION_STATUSES.map(relationTagType)).size).toBe(4);
    expect(relationTagType("PENDING_UPDATE")).toBe("danger");
  });
});

describe("promotionFieldDiffs", () => {
  it("shows only changed fields, including rate and configured platforms", () => {
    const diffs = promotionFieldDiffs(
      { defaultRate: null, platforms: [], inviteCode: null, sheetUrl: null },
      {
        defaultRate: "0.1550",
        platforms: [{ templateId: "7", templateName: "AgentOne", address: "https://shop/ada" }],
        inviteCode: "ADA",
        sheetUrl: null
      }
    );
    const map = new Map(diffs.map(diff => [diff.key, diff]));
    expect([...map.keys()].sort()).toEqual(["defaultRate", "inviteCode", "platforms"]);
    expect(map.get("defaultRate")).toMatchObject({ from: "-", to: "15.5%" });
    expect(map.get("platforms")?.to).toContain("AgentOne");
    expect(map.get("platforms")?.to).toContain("https://shop/ada");
  });

  it("reports platform order changes and keeps unknown backend fields visible", () => {
    const diffs = promotionFieldDiffs(
      { platforms: [{ templateId: "8" }, { templateId: "7" }], extra: "a" },
      { platforms: [{ templateId: "7" }, { templateId: "8" }], extra: "b" }
    );
    expect(diffs.map(diff => diff.key)).toEqual(["platforms", "extra"]);
  });

  it("treats a missing snapshot as empty", () => {
    expect(promotionFieldDiffs(null, { inviteCode: "ADA" })).toContainEqual({
      key: "inviteCode",
      from: "-",
      to: "ADA"
    });
  });
});

describe("missing and incomplete promotion fields", () => {
  it("reads the backend's field array and removes duplicates", () => {
    expect(missingPromotionFields(["defaultRate", "platformAddress", "defaultRate"])).toEqual([
      "defaultRate",
      "platformAddress"
    ]);
    expect(missingPromotionFields({ data: ["review", "sheetUrl"] })).toEqual([
      "review",
      "sheetUrl"
    ]);
    expect(missingPromotionFields({ detail: "unreadable" })).toEqual([]);
  });

  it("requires all configuration fields and review before promotion starts", () => {
    expect(incompletePromotionFields({ review: false })).toEqual([
      "defaultRate",
      "inviteCode",
      "platforms",
      "sheetUrl",
      "review"
    ]);
    expect(
      incompletePromotionFields({
        defaultRate: "0.1000",
        inviteCode: "ADA",
        platforms: [{ templateId: "7", address: "https://shop.example/ada" }],
        jumpTemplateId: "7",
        sheetUrl: "https://docs.google.com/spreadsheets/d/abc",
        review: true
      })
    ).toEqual([]);
    expect(
      incompletePromotionFields({
        defaultRate: "0.1",
        inviteCode: "ADA",
        platforms: [{ templateId: "7", address: "" }],
        jumpTemplateId: "7",
        sheetUrl: "sheet",
        review: true
      })
    ).toEqual(["platformAddress"]);
  });
});
