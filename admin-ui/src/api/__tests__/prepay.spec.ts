import { describe, expect, it, vi } from "vitest";
import { http } from "../http";
import { applyPrepay, payPrepay, savePrepayApplicationDetails } from "../commission/prepay";
vi.mock("../http", () => ({ http: { request: vi.fn() } }));
const read = (blob: Blob) =>
  new Promise<string>(resolve => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.readAsText(blob);
  });
describe("prepay multipart contract", () => {
  it("saves finance details separately and preserves a false deduction choice", async () => {
    vi.mocked(http.request).mockClear();
    const payload = {
      reason: "finance",
      expectedPayDate: "2026-10-23",
      recipientText: "Bank\nRecipient\nFull account 123456789\nCNY",
      deductFromCommission: false
    };
    const attachment = new File(["proof"], "proof.pdf", { type: "application/pdf" });
    savePrepayApplicationDetails("9", payload, [attachment]);
    const config = vi.mocked(http.request).mock.calls[0][0];
    expect(config.url).toBe("/commission/prepays/9/application-details");
    expect(config.method).toBe("put");
    const data = config.data as FormData;
    expect(JSON.parse(await read(data.get("payload") as Blob))).toEqual(payload);
    expect(data.getAll("attachments")).toHaveLength(1);
  });
  it("preserves the minimal JSON payload UUID IDs and money on retry", async () => {
    const payload = {
      requestId: "6fbcbe78-9853-4b7b-8652-a96de00af0b4",
      kolId: "9007199254740993",
      amountCny: "999999999999.99"
    };
    vi.mocked(http.request).mockClear();
    applyPrepay(payload);
    applyPrepay(payload);
    for (const [config] of vi.mocked(http.request).mock.calls) {
      expect(config.url).toBe("/commission/prepays");
      const data = config.data as FormData;
      expect((data.get("payload") as Blob).type).toBe("application/json");
      expect(JSON.parse(await read(data.get("payload") as Blob))).toEqual(payload);
      expect(data.getAll("attachments")).toEqual([]);
    }
  });
  it("registers payment as multipart with a single voucher", async () => {
    const payload = {
      requestId: "6fbcbe78-9853-4b7b-8652-a96de00af0b4",
      payAmountCny: "100.01",
      payTime: "2026-10-22T01:00:00Z",
      payMethod: "PayPal"
    };
    vi.mocked(http.request).mockClear();
    payPrepay(
      "9007199254740993",
      payload,
      new File(["proof"], "voucher.png", { type: "image/png" })
    );
    const config = vi.mocked(http.request).mock.calls[0][0];
    expect(config).toMatchObject({
      url: "/commission/prepays/9007199254740993/paid",
      method: "put"
    });
    const data = config.data as FormData;
    expect(JSON.parse(await read(data.get("payload") as Blob))).toEqual(payload);
    expect(data.getAll("voucher")).toHaveLength(1);
  });
});
