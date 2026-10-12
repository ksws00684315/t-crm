import { describe, expect, it } from "vitest";
import { watermarkInk, watermarkText } from "../watermark";

describe("watermarkText", () => {
  it("joins the nickname, username, and the local calendar date", () => {
    const now = new Date(2026, 0, 2, 0, 30);
    expect(watermarkText("Ada", "ada", now)).toBe("Ada · ada · 2026-01-02");
  });

  it("pads a single-digit local month and day", () => {
    const now = new Date(2026, 8, 3, 23, 30);
    expect(watermarkText("Ada", "ada", now)).toBe("Ada · ada · 2026-09-03");
  });
});

describe("watermarkInk", () => {
  it("turns a text token into a faint rgba fill", () => {
    expect(watermarkInk("#15181D")).toBe("rgba(21, 24, 29, 0.08)");
    expect(watermarkInk("E7E9EC", 0.08)).toBe("rgba(231, 233, 236, 0.08)");
  });

  it("falls back to the light text color when the token cannot be read", () => {
    expect(watermarkInk("")).toBe("rgba(21, 24, 29, 0.08)");
    expect(watermarkInk("var(--tcrm-color-text)")).toBe("rgba(21, 24, 29, 0.08)");
  });
});
