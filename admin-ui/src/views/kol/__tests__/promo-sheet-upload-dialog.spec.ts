import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus, { ElMessage } from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as promoApi from "@/api/kol/promo-sheet";
import type { PromoSheetPreview } from "@/api/kol/promo-sheet";
import { i18n } from "@/locales";
import { ApiError } from "@/utils/http/error";
import * as notify from "@/utils/notify";
import PromoSheetUploadDialog from "../PromoSheetUploadDialog.vue";

vi.mock("@/utils/notify", () => ({ notifyError: vi.fn() }));

vi.mock("@/api/kol/promo-sheet", async importOriginal => ({
  ...(await importOriginal<typeof promoApi>()),
  uploadPromoSheet: vi.fn(),
  confirmPromoSheet: vi.fn()
}));

const preview: PromoSheetPreview = {
  uploadId: "upload-1",
  fileName: "promo.xlsx",
  linkRows: 6,
  duplicates: 1,
  toAdd: 2,
  existing: 1,
  conflicts: 1,
  notInSheet: 1,
  skipCounts: { NON_WEIDIAN: 1, IN_SHEET: 1 },
  skipSamples: [
    { sheet: "ARCHIVE style", row: 6, reason: "NON_WEIDIAN" },
    { sheet: "ARCHIVE style", row: 10, reason: "IN_SHEET" }
  ],
  newItems: [
    {
      shopName: "旗舰店",
      shopWeidianId: "111",
      weidianItemId: "1111111111",
      itemName: "Silver Ring",
      productCode: "P000001"
    },
    {
      shopName: "旗舰店",
      shopWeidianId: "111",
      weidianItemId: "3333333333",
      itemName: "Golden Ring",
      productCode: null
    }
  ],
  conflictsDetail: [
    {
      shopWeidianId: "111",
      weidianItemId: "2222222222",
      itemName: "Theirs",
      ownerKolCode: null,
      ownerKolName: null
    }
  ]
};

async function mountDialog() {
  const wrapper = mount(PromoSheetUploadDialog, {
    props: { modelValue: false, kolId: "5", kolLabel: "Alice (KOL-AAA001)" },
    global: { plugins: [ElementPlus, i18n] },
    attachTo: document.body
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

/** The choose button opens the hidden file input; the test triggers its change directly. */
async function pick(file: File) {
  const hidden = document.body.querySelector<HTMLInputElement>('input[type="file"]')!;
  Object.defineProperty(hidden, "files", { value: [file], configurable: true });
  hidden.dispatchEvent(new Event("change"));
  await flushPromises();
}

describe("Promo sheet upload dialog", () => {
  beforeEach(() => {
    vi.spyOn(ElMessage, "warning").mockImplementation(() => ({}) as never);
  });
  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("uploads, previews, confirms and shows the result", async () => {
    vi.mocked(promoApi.uploadPromoSheet).mockResolvedValue(preview);
    vi.mocked(promoApi.confirmPromoSheet).mockResolvedValue({
      fileName: "promo.xlsx",
      inserted: 2,
      existing: 1,
      conflicts: 1,
      duplicates: 1,
      notInSheet: 1,
      skipCounts: { NON_WEIDIAN: 1, IN_SHEET: 1 },
      conflictsDetail: []
    });
    const wrapper = await mountDialog();
    await pick(new File(["bytes"], "promo.xlsx", { type: "application/octet-stream" }));

    expect(promoApi.uploadPromoSheet).toHaveBeenCalledWith(
      "5",
      expect.any(File),
      expect.anything()
    );
    expect(wrapper.text()).toContain("Alice (KOL-AAA001)");
    expect(wrapper.text()).toContain("1111111111");
    expect(wrapper.text()).toContain("P000001");

    document.body.querySelector<HTMLButtonElement>('[data-test="promo-confirm"]')!.click();
    await flushPromises();
    expect(promoApi.confirmPromoSheet).toHaveBeenCalledWith("5", "upload-1");
    expect(wrapper.text()).toContain("已确认写入");
    expect(wrapper.emitted("confirmed")).toHaveLength(1);
  });

  it("rejects files that are not .xlsx or above the size cap", async () => {
    await mountDialog();
    await pick(new File(["bytes"], "promo.csv", { type: "text/csv" }));
    expect(ElMessage.warning).toHaveBeenCalledWith("只支持 .xlsx 文件。");
    const huge = new File([new Uint8Array(10)], "promo.xlsx");
    Object.defineProperty(huge, "size", { value: 301 * 1024 * 1024 });
    await pick(huge);
    expect(ElMessage.warning).toHaveBeenCalledWith("文件超过 300MB。");
    expect(promoApi.uploadPromoSheet).not.toHaveBeenCalled();
  });

  it("shows the generic owner label when the backend hides the other creator", async () => {
    vi.mocked(promoApi.uploadPromoSheet).mockResolvedValue(preview);
    const wrapper = await mountDialog();
    await pick(new File(["bytes"], "promo.xlsx", { type: "application/octet-stream" }));
    expect(wrapper.text()).toContain("已属于其他达人");
    expect(wrapper.text()).not.toContain("KOL-XXX999");
  });

  it("surfaces an upload failure and lets the user retry", async () => {
    vi.mocked(promoApi.uploadPromoSheet)
      .mockRejectedValueOnce(
        new ApiError({ status: 400, errorCode: "KOL_PROMO_SHEET_INVALID", traceId: "trace" })
      )
      .mockResolvedValueOnce(preview);
    const wrapper = await mountDialog();
    await pick(new File(["bytes"], "promo.xlsx", { type: "application/octet-stream" }));
    expect(notify.notifyError).toHaveBeenCalled();
    expect(document.body.querySelector('[data-test="promo-choose"]')).not.toBeNull();
    await pick(new File(["bytes"], "promo.xlsx", { type: "application/octet-stream" }));
    expect(wrapper.text()).toContain("1111111111");
  });

  it("disables confirmation while nothing would be written", async () => {
    vi.mocked(promoApi.uploadPromoSheet).mockResolvedValue({
      ...preview,
      toAdd: 0,
      newItems: []
    });
    await mountDialog();
    await pick(new File(["bytes"], "promo.xlsx", { type: "application/octet-stream" }));
    const button = document.body.querySelector<HTMLButtonElement>('[data-test="promo-confirm"]')!;
    expect(button.disabled).toBe(true);
    expect(promoApi.confirmPromoSheet).not.toHaveBeenCalled();
  });
});
