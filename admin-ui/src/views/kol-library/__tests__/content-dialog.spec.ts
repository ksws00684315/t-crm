import { DOMWrapper, flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";
import * as libraryApi from "@/api/kol-library/kol";
import type { LibraryContentVO } from "@/api/kol-library/kol";
import { i18n } from "@/locales";
import LibraryContentDialog from "../kol-detail/LibraryContentDialog.vue";

vi.mock("@/api/kol-library/kol");

/**
 * Content-record dialog (requirements 1.2.3, 1.2.5 ⑥, 1.2.6 ③): the three calculated fields follow
 * the inputs with the same formulas as the backend, are read-only, and the API answer stays the
 * source of truth after saving.
 */

const existing: LibraryContentVO = {
  id: "31",
  publishUrl: "https://www.tiktok.com/@amy/video/1",
  publishDate: "2026-10-01",
  contentForm: "SHORT_VIDEO",
  clicks: "1200",
  orders: "30",
  gmv: "1000.00",
  sampleType: "GIFT",
  sampleCost: "30.00",
  slotFee: "50.00",
  commissionRate: "12.50",
  estimatedCommission: "125.00",
  totalCost: "205.00",
  actualRoi: "4.88",
  nextAction: "follow up",
  updateTime: "2026-10-02T00:00:00Z"
};

function inputOf(wrapper: ReturnType<typeof mount>, testId: string): DOMWrapper<HTMLInputElement> {
  const host = wrapper.find(`[data-test="${testId}"]`);
  const field = host.element instanceof HTMLInputElement ? host.element : host.find("input");
  if (!(field instanceof HTMLInputElement)) throw new Error(`missing input ${testId}`);
  return new DOMWrapper(field);
}

function calculated(wrapper: ReturnType<typeof mount>, testId: string): string {
  return wrapper.find(`[data-test="${testId}"]`).text();
}

async function open(content: LibraryContentVO | null) {
  setActivePinia(createPinia());
  const wrapper = mount(LibraryContentDialog, {
    props: { modelValue: false, kolId: "11", content },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

describe("library content dialog", () => {
  beforeEach(() => {
    document.body.innerHTML = "";
    vi.mocked(libraryApi.createLibraryContent).mockReset();
    vi.mocked(libraryApi.updateLibraryContent).mockReset();
  });

  it("shows the stored calculated values when editing", async () => {
    const wrapper = await open(existing);
    expect(calculated(wrapper, "calculated-commission")).toBe("¥125.00");
    expect(calculated(wrapper, "calculated-cost")).toBe("¥205.00");
    expect(calculated(wrapper, "calculated-roi")).toBe("4.88");
    wrapper.unmount();
  });

  it("recalculates the three fields while typing, with the backend formulas", async () => {
    const wrapper = await open(null);
    await inputOf(wrapper, "content-gmv").setValue("1000");
    await inputOf(wrapper, "content-sample-cost").setValue("30");
    await inputOf(wrapper, "content-slot-fee").setValue("50");
    await inputOf(wrapper, "content-rate").setValue("12.5");
    await flushPromises();
    // 1000 × 12.5% = 125.00; 30 + 50 + 125 = 205.00; 1000 / 205 = 4.88
    expect(calculated(wrapper, "calculated-commission")).toBe("¥125.00");
    expect(calculated(wrapper, "calculated-cost")).toBe("¥205.00");
    expect(calculated(wrapper, "calculated-roi")).toBe("4.88");

    // Empty amounts count as 0, so the ROI becomes a dash when nothing is spent.
    await inputOf(wrapper, "content-gmv").setValue("");
    await inputOf(wrapper, "content-sample-cost").setValue("");
    await inputOf(wrapper, "content-slot-fee").setValue("");
    await inputOf(wrapper, "content-rate").setValue("");
    await flushPromises();
    expect(calculated(wrapper, "calculated-commission")).toBe("¥0.00");
    expect(calculated(wrapper, "calculated-cost")).toBe("¥0.00");
    expect(calculated(wrapper, "calculated-roi")).toBe("—");
    wrapper.unmount();
  });

  it("rounds half up like the backend", async () => {
    const wrapper = await open(null);
    // 333.33 × 7.77% = 25.8997… → 25.90
    await inputOf(wrapper, "content-gmv").setValue("333.33");
    await inputOf(wrapper, "content-rate").setValue("7.77");
    await flushPromises();
    expect(calculated(wrapper, "calculated-commission")).toBe("¥25.90");
    // 0.05 × 10% = 0.005 → 0.01 (HALF_UP, not banker's rounding)
    await inputOf(wrapper, "content-gmv").setValue("0.05");
    await inputOf(wrapper, "content-rate").setValue("10");
    await flushPromises();
    expect(calculated(wrapper, "calculated-commission")).toBe("¥0.01");
    wrapper.unmount();
  });

  it("sends only the manual fields and lets the API answer win", async () => {
    vi.mocked(libraryApi.createLibraryContent).mockResolvedValue(existing);
    const wrapper = await open(null);
    await inputOf(wrapper, "content-url").setValue("https://www.tiktok.com/@amy/video/1");
    // The date picker is a text input with the value format.
    await inputOf(wrapper, "content-gmv").setValue("1000");
    await inputOf(wrapper, "content-sample-cost").setValue("30");
    await inputOf(wrapper, "content-slot-fee").setValue("50");
    await inputOf(wrapper, "content-rate").setValue("12.5");
    // The publish date sits in the same form, next to the link: the only date of this dialog.
    const picker = wrapper.findComponent({ name: "ElDatePicker" });
    picker.vm.$emit("update:modelValue", "2026-10-01");
    await flushPromises();
    await wrapper.find('[data-test="content-save"]').trigger("click");
    await vi.waitFor(() => expect(libraryApi.createLibraryContent).toHaveBeenCalled(), {
      timeout: 3000
    });
    const [kolId, payload] = vi.mocked(libraryApi.createLibraryContent).mock.calls[0];
    expect(kolId).toBe("11");
    expect(payload).toMatchObject({
      publishUrl: "https://www.tiktok.com/@amy/video/1",
      publishDate: "2026-10-01",
      gmv: "1000",
      sampleCost: "30",
      slotFee: "50",
      commissionRate: "12.5"
    });
    // The three calculated values never travel to the API.
    expect(payload).not.toHaveProperty("estimatedCommission");
    expect(payload).not.toHaveProperty("totalCost");
    expect(payload).not.toHaveProperty("actualRoi");
    expect(wrapper.emitted("saved")).toHaveLength(1);
    wrapper.unmount();
  });

  it("edits an existing record by its id", async () => {
    vi.mocked(libraryApi.updateLibraryContent).mockResolvedValue(existing);
    const wrapper = await open(existing);
    await inputOf(wrapper, "content-clicks").setValue("5");
    await wrapper.find('[data-test="content-save"]').trigger("click");
    await vi.waitFor(() => expect(libraryApi.updateLibraryContent).toHaveBeenCalled(), {
      timeout: 3000
    });
    expect(vi.mocked(libraryApi.updateLibraryContent).mock.calls[0].slice(0, 2)).toEqual([
      "11",
      "31"
    ]);
    wrapper.unmount();
  });
});
