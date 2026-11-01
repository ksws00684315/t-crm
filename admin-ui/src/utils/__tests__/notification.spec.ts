import { describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import { accessOf, type AbilitySubject } from "@/utils/permission";
import {
  canOpenNotification,
  notificationHref,
  notificationText,
  notificationValues,
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
    ).toBe("/kol/kols");
    expect(notificationHref({ type: "ORDER_SYNC_FAILED", params: {}, bizId: null })).toBe(
      "/order/orders"
    );
    expect(
      notificationHref({
        type: "BD_TRANSFER_CANCELLED",
        params: { kolCode: "KOL 1" },
        bizId: "2"
      })
    ).toBe("/system/oper-logs?module=KOL&keyword=KOL%201");
    expect(canOpenNotification("KOLS_UNASSIGNED", accessOf(staff))).toBe(true);
    expect(canOpenNotification("ORDER_SYNC_FAILED", accessOf(staff))).toBe(false);
    expect(canOpenNotification("BD_TRANSFER_CANCELLED", accessOf(superAdmin))).toBe(true);
    // Accounts are no longer reviewed (task 82): the creator list is the target, so kol:view opens it.
    expect(canOpenNotification("PAYOUT_ACCOUNT_SUBMITTED", accessOf(staff))).toBe(true);
    expect(
      canOpenNotification(
        "PAYOUT_ACCOUNT_SUBMITTED",
        accessOf({ ...staff, permissions: ["payout-account:review"] })
      )
    ).toBe(false);
    expect(canOpenNotification("PAYOUT_ACCOUNT_SUBMITTED", accessOf(superAdmin))).toBe(true);
  });
});

describe("withdrawal notifications", () => {
  it.each([
    "WITHDRAWAL_SUBMITTED",
    "WITHDRAWAL_APPROVED",
    "WITHDRAWAL_REJECTED",
    "WITHDRAWAL_PAID"
  ])("opens %s only with commission:view (the withdrawal tab's own gate)", type => {
    expect(notificationHref({ type, params: {}, bizId: "9007199254740993" })).toBe(
      "/fund/payouts?withdrawalId=9007199254740993"
    );
    expect(
      canOpenNotification(type, accessOf({ ...staff, permissions: ["commission:view"] }))
    ).toBe(true);
    // payout-account:review alone never opens it: the withdrawal tab and its drawer need
    // commission:view, so the notification would land on a tab without the target.
    expect(
      canOpenNotification(type, accessOf({ ...staff, permissions: ["payout-account:review"] }))
    ).toBe(false);
    expect(canOpenNotification(type, accessOf(staff))).toBe(false);
    expect(canOpenNotification(type, accessOf({ ...superAdmin, role: "PLATFORM_ADMIN" }))).toBe(
      false
    );
  });

  it("renders the sentence with the creator identity and application number", () => {
    const item = {
      type: "WITHDRAWAL_SUBMITTED" as const,
      params: { kolCode: "KOL-73", kolName: "Ada", applicationNo: "W20261027001" },
      bizId: "1"
    };
    i18n.global.locale.value = "zh-CN";
    expect(notificationText(item)).toContain("W20261027001");
    expect(notificationValues(item).applicationNo).toBe("W20261027001");
    i18n.global.locale.value = "en-US";
    expect(notificationText(item)).toContain("W20261027001");
    i18n.global.locale.value = "zh-CN";
  });
});

describe("unknown notification types", () => {
  it("falls back to the generic sentence instead of showing the raw type key", () => {
    const item = { type: "FUTURE_TYPE_FROM_NEWER_BACKEND", params: null, bizId: null };
    i18n.global.locale.value = "zh-CN";
    expect(notificationText(item)).toBe("你有一条新消息，请打开消息列表查看");
    i18n.global.locale.value = "en-US";
    expect(notificationText(item)).toBe(
      "You have a new message. Open the notification list to view it"
    );
    i18n.global.locale.value = "zh-CN";
  });
});

describe("prepay notifications", () => {
  it.each(["PREPAY_SUBMITTED", "PREPAY_APPROVED", "PREPAY_REJECTED", "PREPAY_PAID"])(
    "opens %s only with prepay read authority",
    type => {
      expect(notificationHref({ type, params: {}, bizId: "9007199254740993" })).toBe(
        "/fund/prepays?id=9007199254740993"
      );
      expect(canOpenNotification(type, accessOf({ ...staff, permissions: ["prepay:pay"] }))).toBe(
        true
      );
      expect(
        canOpenNotification(type, accessOf({ ...staff, permissions: ["prepay:review"] }))
      ).toBe(true);
      expect(canOpenNotification(type, accessOf(staff))).toBe(false);
      expect(canOpenNotification(type, accessOf({ ...superAdmin, role: "PLATFORM_ADMIN" }))).toBe(
        false
      );
    }
  );
});
