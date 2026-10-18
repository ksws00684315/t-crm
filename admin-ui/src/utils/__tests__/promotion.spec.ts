import { describe, expect, it } from "vitest";
import { looksLikeKolCode, validateKolCode, validateLandingUrl } from "../kol-validation";
import {
  incompletePromotionFields,
  missingPromotionFields,
  promotionFieldDiffs,
  promotionTagType,
  DISPLAY_STATUSES,
  PROMOTION_STATUSES
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

describe("landing page validation", () => {
  it("allows empty and https, rejects other schemes and overlong values", () => {
    expect(validateLandingUrl("")).toBeNull();
    expect(validateLandingUrl("https://brand.example/ada")).toBeNull();
    expect(validateLandingUrl("http://brand.example/ada")).not.toBeNull();
    expect(validateLandingUrl("brand.example")).not.toBeNull();
    expect(validateLandingUrl(`https://brand.example/${"a".repeat(510)}`)).not.toBeNull();
  });
});

describe("promotion status helpers", () => {
  it("gives each shown status its own tag color", () => {
    const colors = DISPLAY_STATUSES.map(promotionTagType);
    expect(new Set(colors).size).toBe(DISPLAY_STATUSES.length);
    expect(promotionTagType("PENDING_UPDATE")).toBe("danger");
  });

  it("lists the stored statuses inside the display statuses", () => {
    expect(DISPLAY_STATUSES).toHaveLength(PROMOTION_STATUSES.length + 1);
    expect(DISPLAY_STATUSES).toContain("PENDING_UPDATE");
  });
});

describe("promotionFieldDiffs", () => {
  it("shows only the changed fields, formatted for display", () => {
    const diffs = promotionFieldDiffs(
      {
        defaultRate: null,
        platformTemplateIds: [],
        landingUrl: null,
        inviteCode: null,
        sheetUrl: null,
        status: "PENDING_PROMOTION"
      },
      {
        defaultRate: "0.1550",
        platformTemplateIds: ["7"],
        landingUrl: null,
        inviteCode: "ADA",
        sheetUrl: null,
        status: "PROMOTING"
      },
      id => (id === "7" ? "AgentOne" : id)
    );
    const map = new Map(diffs.map(diff => [diff.key, diff]));
    expect([...map.keys()].sort()).toEqual([
      "defaultRate",
      "inviteCode",
      "platformTemplateIds",
      "status"
    ]);
    expect(map.get("defaultRate")).toMatchObject({ from: "-", to: "15.5%" });
    expect(map.get("platformTemplateIds")).toMatchObject({ from: "-", to: "AgentOne" });
    expect(map.get("status")).toMatchObject({ from: "PENDING_PROMOTION", to: "PROMOTING" });
  });

  it("reads the backend's own promotionStatus key as the status field", () => {
    // Real records store the live status under `promotionStatus`, not `status`.
    const snapshot = {
      defaultRate: null,
      platformTemplateIds: [],
      landingUrl: null,
      inviteCode: null,
      sheetUrl: null
    };
    const diffs = promotionFieldDiffs(
      { ...snapshot, promotionStatus: "PENDING_PROMOTION" },
      { ...snapshot, promotionStatus: "PROMOTING" }
    );
    expect(diffs).toEqual([{ key: "status", from: "PENDING_PROMOTION", to: "PROMOTING" }]);
  });

  it("keeps platform order irrelevant and keeps unknown fields from a newer backend", () => {
    const diffs = promotionFieldDiffs(
      { platformTemplateIds: ["8", "7"], extra: "a" },
      { platformTemplateIds: ["7", "8"], extra: "b" }
    );
    expect(diffs).toEqual([{ key: "extra", from: "a", to: "b" }]);
  });

  it("treats a missing snapshot as fully empty", () => {
    const diffs = promotionFieldDiffs(null, { inviteCode: "ADA" });
    expect(diffs).toEqual([{ key: "inviteCode", from: "-", to: "ADA" }]);
  });
});

describe("missingPromotionFields", () => {
  it("reads several plausible 422 payload shapes", () => {
    expect(missingPromotionFields({ missingFields: ["defaultRate", "landingUrl"] })).toEqual([
      "defaultRate",
      "landingUrl"
    ]);
    expect(missingPromotionFields(["inviteCode"])).toEqual(["inviteCode"]);
    expect(missingPromotionFields([{ field: "platformTemplateIds", message: "empty" }])).toEqual([
      "platformTemplateIds"
    ]);
    expect(missingPromotionFields({ fields: ["defaultRate", "defaultRate"] })).toEqual([
      "defaultRate"
    ]);
  });

  it("returns nothing for unreadable payloads so the plain message is shown", () => {
    expect(missingPromotionFields(null)).toEqual([]);
    expect(missingPromotionFields({})).toEqual([]);
    expect(missingPromotionFields({ detail: "nope" })).toEqual([]);
  });
});

describe("incompletePromotionFields", () => {
  it("mirrors the backend rule for starting promotion (25a §4.2)", () => {
    expect(incompletePromotionFields({})).toEqual([
      "defaultRate",
      "platformTemplateIds",
      "landingUrl",
      "inviteCode"
    ]);
    expect(
      incompletePromotionFields({
        defaultRate: "0.1000",
        platformTemplateIds: ["7"],
        landingUrl: "https://brand.example",
        inviteCode: "ADA"
      })
    ).toEqual([]);
    expect(incompletePromotionFields({ defaultRate: "0.1000", platformTemplateIds: [] })).toEqual([
      "platformTemplateIds",
      "landingUrl",
      "inviteCode"
    ]);
  });
});
