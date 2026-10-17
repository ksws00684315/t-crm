import { describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import { accessOf, type AbilitySubject } from "@/utils/permission";
import {
  canOpenNotification,
  notificationHref,
  notificationText,
  relativeNotificationTime,
  unreadBadge
} from "@/utils/notification";

const superAdmin: AbilitySubject = {
  role: "SUPER_ADMIN",
  dataScope: "ALL",
  permissions: [],
  postTemplate: null
};

const staff: AbilitySubject = {
  role: "STAFF",
  dataScope: "OWN",
  permissions: ["kol:view"],
  postTemplate: "BD"
};

describe("notification text", () => {
  it("hides a zero badge and caps the rest at 99+", () => {
    expect(unreadBadge(0)).toBeNull();
    expect(unreadBadge(-1)).toBeNull();
    expect(unreadBadge(8)).toBe("8");
    expect(unreadBadge(100)).toBe("99+");
  });

  it("switches the payout sentence with the language", () => {
    const item = {
      type: "PAYOUT_ACCOUNT_SUBMITTED",
      params: { kolCode: "KOL0010", accountType: "PAYPAL" },
      bizId: "77"
    };
    i18n.global.locale.value = "zh-CN";
    expect(notificationText(item)).toBe("达人 KOL0010 提交了 PAYPAL 收款账户，待审核");
    i18n.global.locale.value = "en-US";
    expect(notificationText(item)).toBe(
      "Creator KOL0010 submitted a PAYPAL payout account for review"
    );
    i18n.global.locale.value = "zh-CN";
  });

  it("renders a transfer reason in the current language", () => {
    const item = {
      type: "BD_TRANSFER_CANCELLED",
      params: { kolCode: "KOL0011", reason: "OWNERSHIP_CHANGED" },
      bizId: "21"
    };
    i18n.global.locale.value = "zh-CN";
    expect(notificationText(item)).toContain("执行时归属已变化");
    i18n.global.locale.value = "en-US";
    expect(notificationText(item)).toContain("Ownership changed at execution");
    i18n.global.locale.value = "zh-CN";
  });

  it("uses a relative time inside seven days", () => {
    const now = Date.parse("2026-10-17T12:00:00Z");
    expect(relativeNotificationTime("2026-10-17T11:59:30Z", now)).toBe("刚刚");
    expect(relativeNotificationTime("2026-10-17T11:00:00Z", now)).toBe("1 小时前");
    expect(relativeNotificationTime("2026-10-01T12:00:00Z", now)).toMatch(/2026/);
  });

  it("builds the shared routes and checks page access", () => {
    expect(
      notificationHref({
        type: "PAYOUT_ACCOUNT_SUBMITTED",
        params: {},
        bizId: "77"
      })
    ).toBe("/fund/payout-accounts?id=77");
    expect(notificationHref({ type: "ORDER_SYNC_FAILED", params: {}, bizId: null })).toBe(
      "/order/orders"
    );
    expect(
      notificationHref({
        type: "BD_TRANSFER_CANCELLED",
        params: { kolCode: "KOL 1" },
        bizId: "2"
      })
    ).toBe("/kol/bd-transfers?kolCode=KOL%201");
    expect(canOpenNotification("KOLS_UNASSIGNED", accessOf(staff))).toBe(true);
    expect(canOpenNotification("ORDER_SYNC_FAILED", accessOf(staff))).toBe(false);
    expect(canOpenNotification("BD_TRANSFER_CANCELLED", accessOf(superAdmin))).toBe(true);
    expect(canOpenNotification("PAYOUT_ACCOUNT_SUBMITTED", accessOf(staff))).toBe(false);
    expect(canOpenNotification("PAYOUT_ACCOUNT_SUBMITTED", accessOf(superAdmin))).toBe(true);
  });
});
