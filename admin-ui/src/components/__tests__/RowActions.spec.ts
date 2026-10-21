import { mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { afterEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/locales";
import RowActions from "../RowActions.vue";
import type { RowAction } from "../RowActions.vue";

let wrapper: VueWrapper | undefined;

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
});

function mountActions(actions: RowAction[], maxVisible?: number) {
  wrapper = mount(RowActions, {
    props: maxVisible === undefined ? { actions } : { actions, maxVisible },
    global: { plugins: [ElementPlus, i18n] }
  });
  return wrapper;
}

describe("RowActions", () => {
  it("shows the first two actions as links and folds the rest behind 更多", () => {
    const view = mountActions([
      { key: "edit", label: "编辑", testId: "action-edit" },
      { key: "freeze", label: "冻结", testId: "action-freeze" },
      { key: "cleanup", label: "剩余7天", testId: "action-cleanup", danger: true },
      { key: "prepay", label: "预付", testId: "action-prepay", disabled: true }
    ]);

    expect(view.find('[data-test="action-edit"]').exists()).toBe(true);
    expect(view.find('[data-test="action-freeze"]').exists()).toBe(true);
    expect(view.find('[data-test="more-actions"]').exists()).toBe(true);
    // The dropdown menu is teleported to body; overflow entries live there.
    const cleanup = document.body.querySelector('[data-test="action-cleanup"]');
    expect(cleanup).not.toBeNull();
    expect(cleanup?.className).toContain("actions-cell__danger");
  });

  it("drops hidden actions before splitting visible and overflow entries", () => {
    const view = mountActions([
      { key: "edit", label: "编辑" },
      { key: "secret", label: "隐藏", hidden: true },
      { key: "freeze", label: "冻结" },
      { key: "remove", label: "删除" }
    ]);

    expect(view.text()).not.toContain("隐藏");
    expect(document.body.textContent).not.toContain("隐藏");
    expect(view.find('[data-test="more-actions"]').exists()).toBe(true);
  });

  it("runs visible actions on click and keeps disabled ones inert", async () => {
    const onEdit = vi.fn();
    const onFrozen = vi.fn();
    const view = mountActions([
      { key: "edit", label: "编辑", onClick: onEdit },
      { key: "freeze", label: "冻结", disabled: true, onClick: onFrozen }
    ]);

    const buttons = view.findAll("button");
    await buttons[0].trigger("click");
    await buttons[1].trigger("click");
    expect(onEdit).toHaveBeenCalledTimes(1);
    expect(onFrozen).not.toHaveBeenCalled();
  });

  it("runs overflow actions from the dropdown and keeps disabled hints reachable", async () => {
    const onCleanup = vi.fn();
    const onPrepay = vi.fn();
    mountActions([
      { key: "edit", label: "编辑" },
      { key: "commission", label: "佣金" },
      { key: "cleanup", label: "剩余7天", testId: "action-cleanup", onClick: onCleanup },
      {
        key: "prepay",
        label: "预付",
        testId: "action-prepay",
        disabled: true,
        tooltip: "开发中",
        onClick: onPrepay
      }
    ]);

    expect(document.body.textContent).toContain("剩余7天");
    const prepayItem = document.body.querySelector<HTMLElement>('[data-test="action-prepay"]');
    expect(prepayItem?.getAttribute("title")).toBe("开发中");
    expect(prepayItem?.className).toContain("is-disabled");

    const cleanup = document.body.querySelector<HTMLElement>('[data-test="action-cleanup"]');
    cleanup?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await Promise.resolve();
    expect(onCleanup).toHaveBeenCalledTimes(1);

    prepayItem?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await Promise.resolve();
    expect(onPrepay).not.toHaveBeenCalled();
  });
});
