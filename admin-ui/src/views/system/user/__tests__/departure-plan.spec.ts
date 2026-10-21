import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessageBox } from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as departureApi from "@/api/system/departure-plan";
import * as kolApi from "@/api/kol/kol";
import * as userApi from "@/api/system/user";
import type { UserVO } from "@/api/types";
import { i18n } from "@/locales";
import UserPage from "../index.vue";
import DeparturePlanDialog from "../DeparturePlanDialog.vue";

vi.mock("@/api/system/user");
vi.mock("@/api/kol/kol");
vi.mock("@/api/system/departure-plan");

const bd: UserVO = {
  id: "9007199254740993",
  username: "t41_bd_a",
  nickname: "Alice",
  email: "alice@example.com",
  role: "STAFF",
  postTemplate: "BD",
  permissions: ["kol:view", "kol:edit"],
  dataScope: "OWN",
  tenantId: "1",
  tenantName: "默认公司",
  status: 1,
  lastLoginTime: null,
  createTime: "2026-10-05T01:02:03Z"
};

const pendingPlan = {
  id: "7001",
  userId: bd.id,
  type: "RESIGN" as const,
  effectiveAt: "2030-01-01T10:00:00.000Z",
  heirUserId: null,
  heirName: null,
  initiator: "SELF" as const,
  reason: "family",
  status: "SCHEDULED" as const,
  executedAt: null,
  result: null,
  resultMessage: null,
  awaitingHeir: true
};

function element(testId: string): DOMWrapper<Element> {
  const el = document.body.querySelector(`[data-test="${testId}"]`);
  if (!el) throw new Error(`missing ${testId}`);
  return new DOMWrapper(el);
}

async function openDialog(user: UserVO) {
  const wrapper = mount(DeparturePlanDialog, {
    props: { modelValue: false, user },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

describe("departure plan dialog (task 41)", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.mocked(departureApi.listDepartureHeirs).mockReset();
    vi.mocked(departureApi.createDeparturePlan).mockReset();
    vi.mocked(departureApi.updateDeparturePlan).mockReset();
    vi.mocked(departureApi.listDepartureHeirs).mockResolvedValue([
      { id: "2", username: "t41_bd_b", nickname: "Bob" }
    ]);
    vi.mocked(departureApi.createDeparturePlan).mockResolvedValue({
      ...pendingPlan,
      status: "DONE",
      result: "UNASSIGNED",
      awaitingHeir: false
    });
    vi.mocked(departureApi.updateDeparturePlan).mockResolvedValue(pendingPlan);
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("marks a BD right away with no heir by default", async () => {
    const wrapper = await openDialog(bd);
    await element("departure-submit").trigger("click");
    await flushPromises();
    expect(departureApi.listDepartureHeirs).toHaveBeenCalledWith(bd.id);
    expect(departureApi.createDeparturePlan).toHaveBeenCalledWith(bd.id, {
      type: "RESIGN",
      heirUserId: null,
      reason: null
    });
    expect(wrapper.emitted("saved")).toHaveLength(1);
    expect(wrapper.emitted("update:modelValue")?.at(-1)).toEqual([false]);
    wrapper.unmount();
  });

  it("sends the plain disable type when the administrator picks it", async () => {
    const wrapper = await openDialog(bd);
    await element("departure-type").findAll("input")[1].trigger("click");
    await element("departure-submit").trigger("click");
    await flushPromises();
    expect(departureApi.createDeparturePlan).toHaveBeenCalledWith(
      bd.id,
      expect.objectContaining({ type: "DISABLE" })
    );
    wrapper.unmount();
  });

  it("refuses a scheduled plan without a time before calling the API", async () => {
    const wrapper = await openDialog(bd);
    await element("departure-mode").findAll("input")[1].trigger("click");
    await element("departure-submit").trigger("click");
    await flushPromises();
    expect(element("departure-error").text()).toContain("请选择生效时间");
    expect(departureApi.createDeparturePlan).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it("updates an existing pending plan instead of creating a second one", async () => {
    const wrapper = await openDialog({ ...bd, departurePlan: pendingPlan });
    expect(document.body.textContent).toContain("修改");
    await element("departure-submit").trigger("click");
    await flushPromises();
    expect(departureApi.createDeparturePlan).not.toHaveBeenCalled();
    expect(departureApi.updateDeparturePlan).toHaveBeenCalledWith(bd.id, {
      type: "RESIGN",
      effectiveAt: pendingPlan.effectiveAt,
      heirUserId: null,
      reason: "family"
    });
    wrapper.unmount();
  });

  it("switches a pending plan to immediate by sending now, not omitting the time", async () => {
    const wrapper = await openDialog({ ...bd, departurePlan: pendingPlan });
    // Flip from the pre-filled "at" mode to "立即".
    await element("departure-mode").findAll("input")[0].trigger("click");
    await element("departure-submit").trigger("click");
    await flushPromises();
    expect(departureApi.updateDeparturePlan).toHaveBeenCalled();
    const body = vi.mocked(departureApi.updateDeparturePlan).mock.calls[0][1] as {
      effectiveAt?: string;
    };
    expect(body.effectiveAt).toBeTruthy();
    expect(Date.parse(body.effectiveAt!)).toBeLessThanOrEqual(Date.now());
    wrapper.unmount();
  });

  it("keeps the type locked while editing a pending plan", async () => {
    const wrapper = await openDialog({ ...bd, departurePlan: pendingPlan });
    const group = element("departure-type");
    // Element Plus puts disabled on each radio button, not always on the group root.
    expect(group.findAll("input").every(input => input.attributes("disabled") !== undefined)).toBe(
      true
    );
    // Clicking DISABLE must not change what gets submitted (type stays RESIGN from the plan).
    await group.findAll("input")[1].trigger("click");
    await element("departure-submit").trigger("click");
    await flushPromises();
    expect(departureApi.updateDeparturePlan).toHaveBeenCalledWith(
      bd.id,
      expect.objectContaining({ type: "RESIGN" })
    );
    wrapper.unmount();
  });

  it("allows saving an overdue plan so the administrator can still name an heir", async () => {
    const overdue = {
      ...pendingPlan,
      effectiveAt: "2020-01-01T10:00:00.000Z"
    };
    const wrapper = await openDialog({ ...bd, departurePlan: overdue });
    await element("departure-submit").trigger("click");
    await flushPromises();
    expect(departureApi.updateDeparturePlan).toHaveBeenCalledWith(
      bd.id,
      expect.objectContaining({ effectiveAt: overdue.effectiveAt })
    );
    expect(document.body.querySelector("[data-test=\"departure-error\"]")).toBeNull();
    wrapper.unmount();
  });

  it("spells out the consequence of not naming an heir", async () => {
    const wrapper = await openDialog(bd);
    expect(element("departure-hint").text()).toContain("转为未分配");
    wrapper.unmount();
  });

  it("starts blank for a withdrawn plan instead of resubmitting it", async () => {
    const wrapper = await openDialog({
      ...bd,
      departurePlan: { ...pendingPlan, status: "CANCELLED", awaitingHeir: false }
    });
    // A cancelled plan reads as "no plan" in the list; the dialog must not pre-fill its type,
    // time or reason, or the administrator would recreate it with one click.
    await element("departure-submit").trigger("click");
    await flushPromises();
    expect(departureApi.createDeparturePlan).toHaveBeenCalledWith(bd.id, {
      type: "RESIGN",
      heirUserId: null,
      reason: null
    });
    wrapper.unmount();
  });
});

describe("user list departure column (task 41)", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    document.body.innerHTML = "";
    vi.mocked(kolApi.listKols).mockResolvedValue({
      items: [],
      total: "0",
      page: 1,
      size: 1
    });
    vi.mocked(departureApi.cancelDeparturePlan).mockReset();
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  async function mountPage(items: UserVO[]) {
    vi.mocked(userApi.listUsers).mockResolvedValue({
      items,
      total: String(items.length),
      page: 1,
      size: 20
    });
    const wrapper = mount(UserPage, {
      global: { plugins: [ElementPlus, i18n] },
      attachTo: document.body
    });
    await flushPromises();
    return wrapper;
  }

  /** A row action button; Element Plus also renders a hidden column-measuring copy outside rows. */
  function bodyItem(testId: string): HTMLElement {
    const el = [...document.body.querySelectorAll<HTMLElement>(`[data-test="${testId}"]`)].find(
      item => item.closest("tr") !== null
    );
    if (!el) throw new Error(`missing row action ${testId}`);
    return el;
  }

  it("shows the pending plan with the heir still to be named", async () => {
    const wrapper = await mountPage([{ ...bd, departurePlan: pendingPlan }]);
    const text = wrapper.text();
    expect(text).toContain("离职计划");
    expect(text).toContain("待指定继承人");
    // ADR-029: every row action is inline in the actions column (no "More" menu).
    expect(bodyItem(`departure-cancel-${bd.id}`).textContent).toContain("撤销离职计划");
    wrapper.unmount();
  });

  it("shows the finished state once the plan ran", async () => {
    const wrapper = await mountPage([
      {
        ...bd,
        status: 0,
        departurePlan: {
          ...pendingPlan,
          status: "DONE",
          executedAt: "2030-01-01T10:00:00.000Z",
          result: "TRANSFERRED",
          resultMessage: "达人已转交继承商务",
          awaitingHeir: false
        }
      }
    ]);
    expect(wrapper.text()).toContain("已离职");
    expect(document.body.querySelector(`[data-test="departure-cancel-${bd.id}"]`)).toBeNull();
    wrapper.unmount();
  });

  it("reads a withdrawn plan as no plan", async () => {
    const wrapper = await mountPage([
      { ...bd, departurePlan: { ...pendingPlan, status: "CANCELLED", awaitingHeir: false } }
    ]);
    expect(wrapper.text()).toContain("未设置");
    expect(bodyItem(`departure-${bd.id}`).textContent).toContain("标记离职");
    wrapper.unmount();
  });

  it("cancels a pending plan after the confirmation", async () => {
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    vi.mocked(departureApi.cancelDeparturePlan).mockResolvedValue(null);
    const wrapper = await mountPage([{ ...bd, departurePlan: pendingPlan }]);
    bodyItem(`departure-cancel-${bd.id}`).dispatchEvent(
      new MouseEvent("click", { bubbles: true })
    );
    await flushPromises();
    expect(confirm).toHaveBeenCalled();
    expect(departureApi.cancelDeparturePlan).toHaveBeenCalledWith(bd.id);
    wrapper.unmount();
  });
});
