import { describe, expect, it } from "vitest";
import {
  downloadOrderImportTemplate,
  downloadRefundImportTemplate,
  uploadOrderImport,
  uploadRefundImport
} from "@/api/order/order";
import { downloadKolItemTemplate, uploadKolItemImport } from "@/api/product/kol-item";
import { downloadProductTemplate, uploadProductImport } from "@/api/product/product";
import { i18n } from "@/locales";
import {
  IMPORT_TYPES,
  MAPPING_IMPORT,
  ORDER_IMPORT,
  PRODUCT_IMPORT,
  REFUND_IMPORT,
  importTemplateFileName,
  visibleImportTypes
} from "@/utils/import-types";

const zh = (key: string) => (i18n.global.t as unknown as (k: string) => string)(key);

describe("import type bindings (task 77)", () => {
  it("keeps the same dialog parameters the business pages use", () => {
    expect(IMPORT_TYPES.map(item => item.taskType)).toEqual([
      "PRODUCT_IMPORT",
      "MAPPING_IMPORT",
      "ORDER_IMPORT",
      "REFUND_IMPORT"
    ]);
    expect(PRODUCT_IMPORT).toMatchObject({
      acceptCsv: false,
      requireDataCutoff: false,
      templateFileName: "product-import-template.xlsx",
      downloadTemplate: downloadProductTemplate,
      upload: uploadProductImport
    });
    expect(importTemplateFileName(PRODUCT_IMPORT, zh)).toBe("product-import-template.xlsx");
    expect(zh(PRODUCT_IMPORT.titleKey)).toBe("批量导入原商品");
    expect(zh(PRODUCT_IMPORT.limitHintKey)).toBe("单个文件不超过 20MB、20000 行");

    expect(MAPPING_IMPORT).toMatchObject({
      acceptCsv: false,
      requireDataCutoff: false,
      templateFileName: "kol-item-import-template.xlsx",
      downloadTemplate: downloadKolItemTemplate,
      upload: uploadKolItemImport
    });
    expect(zh(MAPPING_IMPORT.titleKey)).toBe("批量导入对应关系");

    expect(ORDER_IMPORT).toMatchObject({
      acceptCsv: true,
      requireDataCutoff: true,
      downloadTemplate: downloadOrderImportTemplate,
      upload: uploadOrderImport
    });
    expect(importTemplateFileName(ORDER_IMPORT, zh)).toBe("订单导入模板.xlsx");
    expect(zh(ORDER_IMPORT.titleKey)).toBe("导入订单");

    expect(REFUND_IMPORT).toMatchObject({
      acceptCsv: true,
      requireDataCutoff: true,
      downloadTemplate: downloadRefundImportTemplate,
      upload: uploadRefundImport
    });
    expect(importTemplateFileName(REFUND_IMPORT, zh)).toBe("退款导入模板.xlsx");
    expect(zh(REFUND_IMPORT.titleKey)).toBe("导入退款");
    expect(zh(REFUND_IMPORT.limitHintKey)).toBe("单个文件不超过 20MB、20000 行");
  });

  it("lists only the types the existing ability functions allow", () => {
    expect(visibleImportTypes(null)).toEqual([]);
    expect(typesOf({ role: "SUPER_ADMIN", dataScope: "ALL", permissions: [] })).toEqual([
      "PRODUCT_IMPORT",
      "MAPPING_IMPORT",
      "ORDER_IMPORT",
      "REFUND_IMPORT"
    ]);
    expect(typesOf({ role: "STAFF", dataScope: "ALL", permissions: ["product:edit"] })).toEqual([
      "PRODUCT_IMPORT",
      "MAPPING_IMPORT"
    ]);
    expect(typesOf({ role: "STAFF", dataScope: "OWN", permissions: ["order:manage"] })).toEqual([
      "ORDER_IMPORT",
      "REFUND_IMPORT"
    ]);
    expect(typesOf({ role: "STAFF", dataScope: "ALL", permissions: ["product:view"] })).toEqual([]);
    expect(typesOf({ role: "PLATFORM_ADMIN", dataScope: null, permissions: [] })).toEqual([]);
  });
});

function typesOf(user: {
  role: "SUPER_ADMIN" | "STAFF" | "PLATFORM_ADMIN";
  dataScope: "ALL" | "OWN" | null;
  permissions: string[];
}): string[] {
  return visibleImportTypes(user).map(item => item.taskType);
}
