import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
import { routes } from "@/router/routes";

describe("retired creator item route", () => {
  it("redirects legacy direct links to the four-entry workspace", async () => {
    const router = createRouter({ history: createMemoryHistory(), routes });

    await router.push("/items");

    expect(router.currentRoute.value.fullPath).toBe("/");
    expect(router.currentRoute.value.name).toBe("Home");
  });
});
