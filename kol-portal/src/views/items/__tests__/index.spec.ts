import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
import { routes } from "@/router/routes";

describe("creator item route", () => {
  it("keeps legacy direct links available outside the four-entry primary navigation", () => {
    const router = createRouter({ history: createMemoryHistory(), routes });

    const route = router.resolve("/items");

    expect(route.fullPath).toBe("/items");
    expect(route.name).toBe("Items");
  });
});
