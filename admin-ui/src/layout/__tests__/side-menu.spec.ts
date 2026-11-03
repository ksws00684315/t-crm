import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { defineComponent } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import SideMenu from "../SideMenu.vue";

const Blank = defineComponent({ render: () => null });

describe("SideMenu badge (task 102 U-057)", () => {
  it("renders the badge without scaling it and keeps it on the menu type tier", async () => {
    setActivePinia(createPinia());
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/home", component: Blank }]
    });
    await router.push("/home");
    const wrapper = mount(SideMenu, {
      props: {
        items: [{ path: "/home", title: "工作台", badge: 99 }],
        active: "/home",
        collapse: false
      },
      global: { plugins: [ElementPlus, i18n, router] }
    });
    expect(wrapper.find('[data-test="menu-badge"]').exists()).toBe(true);

    // Scoped CSS is not applied in jsdom, so the declarations themselves are the evidence.
    const source = readFileSync(resolve(__dirname, "../SideMenu.vue"), "utf8");
    const start = source.indexOf(".side__badge {");
    const block = source.slice(start, source.indexOf("}", start));
    expect(block).not.toContain("transform");

    const global = readFileSync(resolve(__dirname, "../../style/index.css"), "utf8");
    const menuStart = global.indexOf("#app .side__menu .el-menu-item *");
    const menuBlock = global.slice(menuStart, global.indexOf("}", menuStart));
    expect(menuBlock).toContain("font-size: var(--tcrm-font-size-menu)");
    expect(menuBlock).not.toContain("!important");
  });
});
