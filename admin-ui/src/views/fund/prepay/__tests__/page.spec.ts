import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it, vi } from "vitest";
import { listPrepays } from "@/api/commission/prepay";
import { i18n } from "@/locales";
import { useUserStore } from "@/stores/user";
import Page from "../index.vue";
vi.mock("@/api/commission/prepay", () => ({ listPrepays: vi.fn(), exportPrepays: vi.fn() }));
describe("finance prepay tasks", () => {
  it("offers payment independently from review and opens the selected finance task", async () => {
    setActivePinia(createPinia());
    useUserStore().user = {
      id: "7",
      username: "test",
      nickname: "Test",
      email: null,
      role: "STAFF",
      postTemplate: "BD",
      permissions: ["prepay:pay"],
      dataScope: "ALL",
      tenantId: "1",
      tenantName: "Test",
      status: 1,
      lastLoginTime: null,
      createTime: "2026-10-22T00:00:00Z"
    };
    vi.mocked(listPrepays).mockResolvedValue({
      items: [
        {
          id: "9007199254740993",
          prepayNo: "PP-test",
          financeTaskType: "PREPAY",
          financeTaskId: "9007199254740993",
          kolId: "3",
          kolCode: "KOL-test",
          kolName: "Test",
          applicantId: "7",
          amountCny: "100.00",
          currency: "CNY",
          status: "APPROVED",
          createdAt: "2026-10-22T00:00:00Z"
        }
      ],
      total: "1",
      page: 1,
      size: 20
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/", component: Page }]
    });
    await router.push("/");
    const wrapper = mount(Page, {
      global: {
        plugins: [ElementPlus, i18n, router],
        stubs: {
          DetailDrawer: {
            name: "DetailDrawer",
            props: ["modelValue", "id", "initialAction"],
            template: "<div />"
          }
        }
      }
    });
    await flushPromises();
    expect(wrapper.find('[data-test="prepay-row-approve"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="prepay-row-reject"]').exists()).toBe(false);
    await wrapper.get('[data-test="prepay-row-pay"]').trigger("click");
    expect(wrapper.getComponent({ name: "DetailDrawer" }).props()).toMatchObject({
      modelValue: true,
      id: "9007199254740993",
      initialAction: "pay"
    });
    wrapper.getComponent({ name: "DetailDrawer" }).vm.$emit("saved");
    await flushPromises();
    expect(listPrepays).toHaveBeenCalledTimes(2);
    expect(wrapper.getComponent({ name: "DataTable" }).props("exportable")).toBe(false);
    await wrapper.get('[data-test="prepay-filter-number"]').setValue(" PP-test ");
    wrapper.getComponent({ name: "ListSearch" }).vm.$emit("search");
    await flushPromises();
    expect(listPrepays).toHaveBeenLastCalledWith(
      expect.objectContaining({ prepayNo: "PP-test", page: 1 })
    );
    wrapper.getComponent({ name: "DataTable" }).vm.$emit("update:page", 2);
    await flushPromises();
    expect(listPrepays).toHaveBeenLastCalledWith(
      expect.objectContaining({ prepayNo: "PP-test", page: 2 })
    );
    useUserStore().user!.permissions = ["prepay:pay", "data:export"];
    await flushPromises();
    expect(wrapper.getComponent({ name: "DataTable" }).props("exportable")).toBe(true);
    wrapper.unmount();
  });
});
