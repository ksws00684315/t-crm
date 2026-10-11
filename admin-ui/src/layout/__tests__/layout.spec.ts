import { mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it, vi } from "vitest";
import type { UserVO } from "@/api/types";
import { routes } from "@/router/routes";
import Layout from "../index.vue";

const storeState = vi.hoisted(() => ({ user: null as Record<string, unknown> | null }));

vi.mock("@/stores/user", () => ({
  useUserStore: () => ({
    user: storeState.user,
    roles: storeState.user ? [storeState.user.role] : [],
    refreshProfile: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn()
  })
}));

describe("layout tenant label", () => {
  it("shows the tenant name next to a company user", async () => {
    storeState.user = {
      id: "1",
      username: "alice",
      nickname: "Alice",
      email: null,
      role: "SUPER_ADMIN",
      dataScope: "ALL",
      tenantId: "1",
      tenantName: "北美公司",
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    } satisfies UserVO;
    const router = createRouter({ history: createMemoryHistory(), routes });
    await router.push("/home");
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, router], stubs: { RouterView: true } }
    });

    expect(wrapper.text()).toContain("北美公司");
  });

  it("shows platform management for a platform administrator", () => {
    storeState.user = {
      id: "2",
      username: "platform",
      nickname: "Platform",
      email: null,
      role: "PLATFORM_ADMIN",
      dataScope: "ALL",
      tenantId: null,
      tenantName: null,
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    } satisfies UserVO;
    const router = createRouter({ history: createMemoryHistory(), routes });
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, router], stubs: { RouterView: true } }
    });

    expect(wrapper.text()).toContain("平台管理");
  });

  it("tolerates an older user response without tenantName", () => {
    storeState.user = {
      id: "3",
      username: "legacy",
      nickname: "Legacy",
      email: null,
      role: "OPERATOR",
      dataScope: "ALL",
      tenantId: null,
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-05T00:00:00Z"
    };
    const router = createRouter({ history: createMemoryHistory(), routes });
    const wrapper = mount(Layout, {
      global: { plugins: [ElementPlus, router], stubs: { RouterView: true } }
    });

    expect(wrapper.text()).not.toContain("undefined");
    expect(wrapper.text()).not.toContain("null");
  });
});
