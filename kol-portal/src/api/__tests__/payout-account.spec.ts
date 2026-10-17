import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useSessionStore } from "@/stores/session";
import { http } from "../http";
import * as api from "../payout-account";

function envelope(data: unknown) {
  return {
    status: 200,
    statusText: "",
    headers: {},
    config: {} as never,
    data: { code: 0, msg: "Success", data, errorCode: null, traceId: "test" }
  };
}

describe("payout account API contract", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
  });

  it("sends the right methods, paths and payloads without identity parameters", async () => {
    const requests: {
      method?: string;
      url?: string;
      data?: unknown;
      contentType?: unknown;
    }[] = [];
    http.instance.defaults.adapter = async config => {
      requests.push({
        method: config.method,
        url: config.url,
        data: config.data,
        contentType: config.headers?.get("Content-Type")
      });
      if (config.url === "/files") {
        // The upload must stay a real FormData (not a JSON string) with a multipart header.
        expect(config.data).toBeInstanceOf(FormData);
        expect(config.headers?.get("Content-Type")).toContain("multipart/form-data");
        return envelope({ fileId: "66" });
      }
      return envelope(
        config.url === "/payout-accounts" && config.method === "get"
          ? []
          : config.url === "/payout-accounts/pending"
            ? null
            : { id: "11" }
      );
    };

    await api.listPayoutAccounts();
    await api.createPayoutAccount({
      accountType: "USDT",
      account: "TQ5xK9aZPn7vQm",
      network: "TRC20",
      qrFileId: "66"
    });
    await api.updatePayoutAccount("11", {
      accountType: "PAYPAL",
      account: "ada@example.com",
      holderName: "Ada"
    });
    await api.setDefaultPayoutAccount("11");
    await api.disablePayoutAccount("11");
    await api.uploadQrFile(new File([new Uint8Array([1])], "qr.png", { type: "image/png" }));

    expect(requests).toEqual([
      { method: "get", url: "/payout-accounts", data: undefined, contentType: "application/json" },
      {
        method: "post",
        url: "/payout-accounts",
        // axios has already run transformRequest, so JSON bodies arrive as strings here.
        data: '{"accountType":"USDT","account":"TQ5xK9aZPn7vQm","network":"TRC20","qrFileId":"66"}',
        contentType: "application/json"
      },
      {
        method: "put",
        url: "/payout-accounts/11",
        data: '{"accountType":"PAYPAL","account":"ada@example.com","holderName":"Ada"}',
        contentType: "application/json"
      },
      {
        method: "put",
        url: "/payout-accounts/11/default",
        data: undefined,
        contentType: "application/json"
      },
      {
        method: "put",
        url: "/payout-accounts/11/disable",
        data: undefined,
        contentType: "application/json"
      },
      {
        method: "post",
        url: "/files",
        data: expect.any(FormData),
        contentType: expect.stringContaining("multipart/form-data")
      }
    ]);
  });

  it("downloads the QR blob through the file endpoint with the token in the header", async () => {
    useSessionStore().setSession({ token: "kol-token", expiresAt: Date.now() + 60000 });
    let sawToken = false;
    http.instance.defaults.adapter = async config => {
      sawToken = config.headers?.get("X-Kol-Token") === "kol-token";
      return {
        status: 200,
        statusText: "",
        headers: {},
        config,
        data: new Blob([new Uint8Array([1])], { type: "image/png" })
      };
    };
    const blob = await api.downloadQrFile("66");
    expect(blob).toBeInstanceOf(Blob);
    expect(sawToken).toBe(true);
  });
});
