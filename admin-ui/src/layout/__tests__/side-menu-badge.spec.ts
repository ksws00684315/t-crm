import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import SideMenu from "../SideMenu.vue";

function mountMenu(badge?: number) {
  return mount(SideMenu, {
    props: {
      items: [
        {
          path: "/fund",
          title: "资金",
          children: [
            { path: "/fund/payout-accounts", title: "收款账户审核", badge },
            { path: "/fund/other", title: "其他" }
          ]
        }
      ],
      active: "/fund/payout-accounts",
      collapse: false
    },
    global: { plugins: [ElementPlus, i18n] }
  });
}

describe("side menu badge", () => {
  it("shows the pending count next to the review entry", () => {
    const wrapper = mountMenu(3);
    const badges = wrapper.findAll('[data-test="menu-badge"]');
    expect(badges).toHaveLength(1);
    expect(badges[0].text()).toContain("3");
    expect(wrapper.text()).toContain("收款账户审核");
  });

  it("renders no badge for zero or missing counts", () => {
    expect(mountMenu(0).find('[data-test="menu-badge"]').exists()).toBe(false);
    expect(mountMenu(undefined).find('[data-test="menu-badge"]').exists()).toBe(false);
  });
});
