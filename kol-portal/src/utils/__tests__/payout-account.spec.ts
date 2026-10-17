import { describe, expect, it } from "vitest";
import { ALNUM_ACCOUNT, checkQrFile, QR_MAX_BYTES, USDT_NETWORKS } from "../payout-account";

function file(type: string, size: number, name = "qr.bin"): File {
  return new File([new Uint8Array(size)], name, { type });
}

describe("USDT networks", () => {
  it("lists the five networks from the design in a stable order", () => {
    expect([...USDT_NETWORKS]).toEqual(["TRC20", "ERC20", "BEP20", "SOL", "TON"]);
  });
});

describe("ALNUM_ACCOUNT", () => {
  it("accepts letters and digits of 3-128 characters and nothing else", () => {
    expect(ALNUM_ACCOUNT.test("TQ5xK9aZ")).toBe(true);
    expect("a".repeat(3)).toMatch(ALNUM_ACCOUNT);
    expect("a".repeat(128)).toMatch(ALNUM_ACCOUNT);
    expect("ab").not.toMatch(ALNUM_ACCOUNT);
    expect("a".repeat(129)).not.toMatch(ALNUM_ACCOUNT);
    expect("TQ5x K9aZ").not.toMatch(ALNUM_ACCOUNT);
    expect("TQ5x-K9aZ").not.toMatch(ALNUM_ACCOUNT);
    expect("地址abcd123").not.toMatch(ALNUM_ACCOUNT);
  });
});

describe("checkQrFile", () => {
  it("accepts PNG and JPEG within 2MB", () => {
    expect(checkQrFile(file("image/png", 1024))).toBe("ok");
    expect(checkQrFile(file("image/jpeg", QR_MAX_BYTES))).toBe("ok");
  });

  it("falls back to the file name when the MIME type is empty", () => {
    expect(checkQrFile(file("", 1024, "qr.png"))).toBe("ok");
    expect(checkQrFile(file("", 1024, "qr.JPG"))).toBe("ok");
    expect(checkQrFile(file("", 1024, "qr.gif"))).toBe("type");
  });

  it("rejects other types and anything above 2MB", () => {
    expect(checkQrFile(file("image/gif", 1024))).toBe("type");
    expect(checkQrFile(file("application/pdf", 1024))).toBe("type");
    expect(checkQrFile(file("image/png", QR_MAX_BYTES + 1))).toBe("size");
  });
});
