import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as userApi from "@/api/system/user";
import type { UserVO } from "@/api/types";
import UserPage from "../index.vue";

vi.mock("@/api/system/user");

const row: UserVO = {
  id: "9007199254740993",
  username: "alice",
  nickname: "Alice",
  email: "alice@example.com",
  role: "OPERATOR",
  dataScope: "OWN",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T01:02:03Z"
};

describe("user management page", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("loads the first page and renders the rows in Chinese", async () => {
    vi.mocked(userApi.listUsers).mockResolvedValue({ items: [row], total: 1, page: 1, size: 20 });
    const wrapper = mount(UserPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(userApi.listUsers).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      username: undefined,
      role: undefined,
      status: undefined
    });
    const text = wrapper.text();
    expect(text).toContain("alice");
    expect(text).toContain("运营");
    expect(text).toContain("仅本人");
    expect(text).toContain("启用");
    expect(text).toContain("重置密码");
  });
});
