import {
  downloadOrderImportTemplate,
  downloadRefundImportTemplate,
  uploadOrderImport,
  uploadRefundImport
} from "@/api/order/order";
import { downloadKolItemTemplate, uploadKolItemImport } from "@/api/product/kol-item";
import { downloadProductTemplate, uploadProductImport } from "@/api/product/product";
import type { DownloadedFile } from "@/utils/http";
import { orderAbilities, productAbilities, type AbilitySubject } from "@/utils/permission";

/**
 * ImportDialog parameters shared by the business pages and the Uploads page (task 77).
 * Order and refund download names are localized; product and mapping names are fixed literals.
 * Creator import is intentionally not an entry.
 */
export interface ImportTypeBinding {
  taskType: "PRODUCT_IMPORT" | "MAPPING_IMPORT" | "ORDER_IMPORT" | "REFUND_IMPORT";
  titleKey: string;
  limitHintKey: string;
  templateFileName: string;
  templateFileNameKey: string | null;
  downloadTemplate: () => Promise<DownloadedFile>;
  upload: (file: File, dataCutoffTime?: string) => Promise<{ taskId: string }>;
  acceptCsv: boolean;
  requireDataCutoff: boolean;
}

export const PRODUCT_IMPORT: ImportTypeBinding = {
  taskType: "PRODUCT_IMPORT",
  titleKey: "product.importTitle",
  limitHintKey: "product.limitHint",
  templateFileName: "product-import-template.xlsx",
  templateFileNameKey: null,
  downloadTemplate: downloadProductTemplate,
  upload: uploadProductImport,
  acceptCsv: false,
  requireDataCutoff: false
};

export const MAPPING_IMPORT: ImportTypeBinding = {
  taskType: "MAPPING_IMPORT",
  titleKey: "mapping.importTitle",
  limitHintKey: "mapping.limitHint",
  templateFileName: "kol-item-import-template.xlsx",
  templateFileNameKey: null,
  downloadTemplate: downloadKolItemTemplate,
  upload: uploadKolItemImport,
  acceptCsv: false,
  requireDataCutoff: false
};

export const ORDER_IMPORT: ImportTypeBinding = {
  taskType: "ORDER_IMPORT",
  titleKey: "order.orderImportTitle",
  limitHintKey: "order.importLimitHint",
  templateFileName: "",
  templateFileNameKey: "order.orderTemplateFile",
  downloadTemplate: downloadOrderImportTemplate,
  upload: uploadOrderImport,
  acceptCsv: true,
  requireDataCutoff: true
};

export const REFUND_IMPORT: ImportTypeBinding = {
  taskType: "REFUND_IMPORT",
  titleKey: "order.refundImportTitle",
  limitHintKey: "order.importLimitHint",
  templateFileName: "",
  templateFileNameKey: "order.refundTemplateFile",
  downloadTemplate: downloadRefundImportTemplate,
  upload: uploadRefundImport,
  acceptCsv: true,
  requireDataCutoff: true
};

/** Upload-page order, matching the task table. */
export const IMPORT_TYPES: readonly ImportTypeBinding[] = [
  PRODUCT_IMPORT,
  MAPPING_IMPORT,
  ORDER_IMPORT,
  REFUND_IMPORT
];

export function importTemplateFileName(
  binding: ImportTypeBinding,
  translateKey: (key: string) => string
): string {
  return binding.templateFileNameKey
    ? translateKey(binding.templateFileNameKey)
    : binding.templateFileName;
}

/** Types this account may start. Visibility comes only from the existing ability functions. */
export function visibleImportTypes(user: AbilitySubject | null | undefined): ImportTypeBinding[] {
  const product = productAbilities(user);
  const order = orderAbilities(user);
  return IMPORT_TYPES.filter(binding => {
    switch (binding.taskType) {
      case "PRODUCT_IMPORT":
        return product.canEditProduct;
      case "MAPPING_IMPORT":
        return product.canImportMapping;
      case "ORDER_IMPORT":
      case "REFUND_IMPORT":
        return order.canManage;
    }
  });
}
