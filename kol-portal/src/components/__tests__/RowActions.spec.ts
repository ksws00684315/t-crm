import { mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/locales";
import RowActions from "../RowActions.vue";
import type { RowAction } from "../RowActions.vue";

/** The admin specs assert Chinese copy; the portal defaults to English, so pin the language here. */
beforeEach(() => {
  i18n.global.locale.value = "zh-CN";
});

let wrapper: VueWrapper | undefined;

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
});

function mountActions(actions: RowAction[]) {
  wrapper = mount(RowActions, {
    props: { actions },
    global: { plugins: [ElementPlus, i18n] }
  });
  return wrapper;
}

describe("RowActions", () => {
  it("shows every action inline in the declared order without a More menu", () => {
    const view = mountActions([
      { key: "edit", label: "资料编辑", testId: "action-edit" },
      { key: "commission", label: "佣金信息", testId: "action-commission" },
      { key: "prepay", label: "预付金额", testId: "action-prepay", disabled: true },
      { key: "cleanup", label: "剩余7天", testId: "action-cleanup", danger: true },
      { key: "freeze", label: "冻结", testId: "action-freeze" }
    ]);

    expect(view.findAll("button").map(button => button.text())).toEqual([
      "资料编辑",
      "佣金信息",
      "预付金额",
      "剩余7天",
      "冻结"
    ]);
    expect(view.text()).not.toContain("更多");
    expect(view.find('[data-test="action-cleanup"]').classes()).toContain("actions-cell__danger");
  });

  it("does not render hidden actions", () => {
    const view = mountActions([
      { key: "edit", label: "编辑" },
      { key: "secret", label: "隐藏", hidden: true },
      { key: "freeze", label: "冻结" }
    ]);

    expect(view.text()).not.toContain("隐藏");
    expect(view.findAll("button")).toHaveLength(2);
  });

  it("runs enabled actions on click and keeps disabled ones inert", async () => {
    const onEdit = vi.fn();
    const onPrepay = vi.fn();
    const view = mountActions([
      { key: "edit", label: "编辑", testId: "action-edit", onClick: onEdit },
      {
        key: "prepay",
        label: "预付",
        testId: "action-prepay",
        disabled: true,
        tooltip: "开发中",
        onClick: onPrepay
      }
    ]);

    await view.find('[data-test="action-edit"]').trigger("click");
    await view.find('[data-test="action-prepay"]').trigger("click");
    expect(onEdit).toHaveBeenCalledTimes(1);
    expect(onPrepay).not.toHaveBeenCalled();
    expect(view.find('[data-test="action-prepay"]').attributes("disabled")).toBeDefined();
  });
});
