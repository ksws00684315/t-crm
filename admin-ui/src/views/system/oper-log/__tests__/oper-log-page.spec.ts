import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { describe, expect, it, vi } from "vitest";
import * as operLogApi from "@/api/system/oper-log";
import OperLogPage from "../index.vue";

vi.mock("@/api/system/oper-log");

describe("oper-log page", () => {
  it("converts a string total from the API so el-pagination renders", async () => {
    vi.mocked(operLogApi.listOperLogs).mockResolvedValue({
      items: [],
      total: "7",
      page: 1,
      size: 20
    });
    const wrapper = mount(OperLogPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    const pagination = wrapper.findComponent({ name: "ElPagination" });
    expect(pagination.exists()).toBe(true);
    expect(pagination.props("total")).toBe(7);
    expect(typeof pagination.props("total")).toBe("number");
  });
});
