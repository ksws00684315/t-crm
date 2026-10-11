import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as tenantApi from "@/api/platform/tenant";
import { ApiError } from "@/utils/http/error";
import TenantAdminsDialog from "../TenantAdminsDialog.vue";
import TenantFormDialog from "../TenantFormDialog.vue";
import TenantPage from "../index.vue";

vi.mock("@/api/platform/tenant");

const tenant: tenantApi.TenantVO = {
  id: "9007199254740993",
  tenantCode: "north-america",
  name: "North America",
  status: 1,
  remark: "Primary company",
  createTime: "2026-10-05T01:02:03Z"
};

const admin: tenantApi.TenantAdminVO = {
  id: "9007199254740994",
  username: "alice.admin",
  nickname: "Alice",
  email: "alice@example.com",
  status: 1,
  lastLoginTime: "2026-10-10T10:00:00Z",
  createTime: "2026-10-05T01:02:03Z"
};

const mountOptions = { global: { plugins: [ElementPlus], stubs: { teleport: true } } };

describe("platform tenant management", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("loads and renders the tenant list", async () => {
    vi.mocked(tenantApi.listTenants).mockResolvedValue([tenant]);
    const wrapper = mount(TenantPage, mountOptions);
    await flushPromises();

    expect(tenantApi.listTenants).toHaveBeenCalledOnce();
    expect(wrapper.text()).toContain("north-america");
    expect(wrapper.text()).toContain("North America");
    expect(wrapper.text()).toContain("启用");
    expect(wrapper.text()).toContain("Primary company");
  });

  it("does not call create when the tenant code is invalid", async () => {
    const wrapper = mount(TenantFormDialog, {
      ...mountOptions,
      props: { modelValue: true, tenant: null }
    });
    await flushPromises();
    const inputs = wrapper.findAll("input");
    await inputs[0].setValue("Bad_Code");
    await inputs[1].setValue("A new company");
    await wrapper.get("textarea").setValue("");
    await wrapper.find("button.el-button--primary").trigger("click");
    await flushPromises();

    expect(tenantApi.createTenant).not.toHaveBeenCalled();
  });

  it("shows the tenant code conflict returned by the API", async () => {
    const messageError = vi.spyOn(ElMessage, "error");
    vi.mocked(tenantApi.createTenant).mockRejectedValue(
      new ApiError({ status: 409, errorCode: "TENANT_CODE_EXISTS", traceId: "trace-tenant" })
    );
    const wrapper = mount(TenantFormDialog, {
      ...mountOptions,
      props: { modelValue: true, tenant: null }
    });
    await flushPromises();
    const inputs = wrapper.findAll("input");
    await inputs[0].setValue("north-america");
    await inputs[1].setValue("North America");
    await wrapper.find("button.el-button--primary").trigger("click");
    await flushPromises();

    expect(tenantApi.createTenant).toHaveBeenCalledWith({
      tenantCode: "north-america",
      name: "North America",
      remark: null
    });
    expect(wrapper.findAllComponents({ name: "ElFormItem" })[0].props("error")).toBe(
      "公司编码已存在"
    );
    expect(messageError).toHaveBeenCalledWith("公司编码已存在");
  });

  it("asks before disabling a tenant and updates status after confirmation", async () => {
    vi.mocked(tenantApi.listTenants).mockResolvedValue([tenant]);
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.mocked(tenantApi.updateTenantStatus).mockResolvedValue(null);
    const wrapper = mount(TenantPage, mountOptions);
    await flushPromises();
    const buttons = wrapper.findAll("button");
    await buttons.find(button => button.text() === "停用")?.trigger("click");
    await flushPromises();

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      "停用后，该公司所有管理员将无法登录，已登录的会在下一次操作时退出；中转链接不受影响。",
      "停用公司",
      { type: "warning" }
    );
    expect(tenantApi.updateTenantStatus).toHaveBeenCalledWith(tenant.id, 0);
  });

  it("loads tenant admins, creates one, and resets its password", async () => {
    const messageSuccess = vi.spyOn(ElMessage, "success");
    vi.mocked(tenantApi.listTenantAdmins).mockResolvedValue([admin]);
    vi.mocked(tenantApi.createTenantAdmin).mockResolvedValue(admin);
    vi.mocked(tenantApi.resetTenantAdminPassword).mockResolvedValue(null);
    const wrapper = mount(TenantAdminsDialog, {
      ...mountOptions,
      props: { modelValue: true, tenant }
    });
    await flushPromises();

    expect(tenantApi.listTenantAdmins).toHaveBeenCalledWith(tenant.id);
    expect(wrapper.text()).toContain("alice.admin");
    expect(wrapper.text()).toContain("Alice");

    await wrapper
      .findAll("button")
      .find(button => button.text() === "新建超级管理员")
      ?.trigger("click");
    await flushPromises();
    const createInputs = wrapper.findAll("input");
    await createInputs[0].setValue("bob.admin");
    await createInputs[1].setValue("Bob Admin");
    await createInputs[2].setValue("bob@example.com");
    await createInputs[3].setValue("StrongPass!42");
    await wrapper
      .findAll("button")
      .find(button => button.text() === "创建")
      ?.trigger("click");
    await flushPromises();

    expect(tenantApi.createTenantAdmin).toHaveBeenCalledWith(tenant.id, {
      username: "bob.admin",
      nickname: "Bob Admin",
      email: "bob@example.com",
      password: "StrongPass!42"
    });

    await wrapper
      .findAll("button")
      .find(button => button.text() === "重置密码")
      ?.trigger("click");
    await flushPromises();
    const resetInputs = wrapper.findAll("input");
    await resetInputs[resetInputs.length - 1].setValue("AnotherPass!43");
    await wrapper
      .findAll("button")
      .find(button => button.text() === "确定")
      ?.trigger("click");
    await flushPromises();

    expect(tenantApi.resetTenantAdminPassword).toHaveBeenCalledWith(
      tenant.id,
      admin.id,
      "AnotherPass!43"
    );
    expect(messageSuccess).toHaveBeenCalledWith("该管理员已在所有设备上退出");
  });
});
