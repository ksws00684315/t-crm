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
  publishTitle: "Amy video",
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

async function open(content: LibraryContentVO | null) {
  setActivePinia(createPinia());
  const wrapper = mount(LibraryContentDialog, {
    props: { modelValue: false, kolId: "11", content, canEdit: true },
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

  it("requires a title and creates with the remaining optional fields blank", async () => {
    vi.mocked(libraryApi.createLibraryContent).mockResolvedValue(existing);
    const wrapper = await open(null);
    await inputOf(wrapper, "content-url").setValue("https://www.tiktok.com/@amy/video/1");
    wrapper.findComponent({ name: "ElDatePicker" }).vm.$emit("update:modelValue", "2026-10-01");
    await wrapper.get('[data-test="content-save"]').trigger("click");
    await flushPromises();
    expect(libraryApi.createLibraryContent).not.toHaveBeenCalled();
    await inputOf(wrapper, "content-title").setValue("Amy video");
    await wrapper.get('[data-test="content-save"]').trigger("click");
    await vi.waitFor(
      () =>
        expect(libraryApi.createLibraryContent).toHaveBeenCalledWith("11", {
          publishUrl: "https://www.tiktok.com/@amy/video/1",
          publishTitle: "Amy video",
          publishDate: "2026-10-01",
          contentForm: "SHORT_VIDEO",
          commissionRate: null,
          sampleCost: null,
          sampleStatus: null,
          slotFee: null,
          nextAction: null,
          remark: null
        }),
      { timeout: 3000 }
    );
    wrapper.unmount();
  });

  it("edits only basic information from the read-only drawer", async () => {
    vi.mocked(libraryApi.updateLibraryContent).mockResolvedValue(existing);
    const wrapper = await open(existing);
    expect(wrapper.find('[data-test="content-url"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain("带货GMV");
    await wrapper.get('[data-test="content-enable-edit"]').trigger("click");
    expect(wrapper.find('[data-test="content-clicks"]').exists()).toBe(false);
    await wrapper.get('textarea[data-test="content-remark"]').setValue("Sample received");
    await wrapper.get('[data-test="content-save"]').trigger("click");
    await vi.waitFor(
      () =>
        expect(libraryApi.updateLibraryContent).toHaveBeenCalledWith(
          "11",
          "31",
          expect.objectContaining({ remark: "Sample received" })
        ),
      { timeout: 3000 }
    );
    const request = vi.mocked(libraryApi.updateLibraryContent).mock.calls[0][2];
    expect(request).not.toHaveProperty("clicks");
    expect(request).not.toHaveProperty("orders");
    expect(request).not.toHaveProperty("gmv");
    wrapper.unmount();
  });
});
