import { beforeEach } from "vitest";
import { i18n } from "@/locales";

/** Existing specs assert Chinese copy. Pin the singleton before each test so order cannot flake. */
beforeEach(() => {
  i18n.global.locale.value = "zh-CN";
});
