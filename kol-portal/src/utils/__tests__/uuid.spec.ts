import { afterEach, describe, expect, it, vi } from "vitest";
import { randomUuid } from "../uuid";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** Hides crypto.randomUUID the way a plain-HTTP non-localhost context does (#133 item 2). */
function withoutRandomUuid<T>(run: () => T): T {
  const descriptor = Object.getOwnPropertyDescriptor(crypto, "randomUUID");
  Object.defineProperty(crypto, "randomUUID", { value: undefined, configurable: true });
  try {
    return run();
  } finally {
    if (descriptor) Object.defineProperty(crypto, "randomUUID", descriptor);
  }
}

describe("randomUuid", () => {
  afterEach(() => vi.restoreAllMocks());

  it("delegates to crypto.randomUUID when the context is secure", () => {
    const spy = vi
      .spyOn(crypto, "randomUUID")
      .mockReturnValue("4b2f8a1e-2b3c-4d5e-8f90-a1b2c3d4e5f6");
    expect(randomUuid()).toBe("4b2f8a1e-2b3c-4d5e-8f90-a1b2c3d4e5f6");
    expect(spy).toHaveBeenCalledOnce();
  });

  it("builds an RFC 4122 v4 uuid from getRandomValues without randomUUID", () => {
    const ids = withoutRandomUuid(() => [randomUuid(), randomUuid(), randomUuid()]);
    for (const id of ids) {
      expect(id).toMatch(UUID_V4);
    }
    expect(new Set(ids).size).toBe(ids.length);
  });
});
