<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import {
  confirmPromoSheet,
  uploadPromoSheet,
  PROMO_SHEET_MAX_BYTES,
  type PromoSheetConfirmResult,
  type PromoSheetPreview,
  type PromoSheetSkipReason
} from "@/api/kol/promo-sheet";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "PromoSheetUploadDialog" });

const props = defineProps<{
  modelValue: boolean;
  kolId: string | null;
  kolLabel: string;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "confirmed"): void;
}>();

const { t } = useI18n();

type Phase = "select" | "uploading" | "preview" | "confirming" | "result";

const phase = ref<Phase>("select");
const fileInput = ref<HTMLInputElement | null>(null);
const selectedFile = ref<File | null>(null);
const progress = ref(0);
const preview = ref<PromoSheetPreview | null>(null);
const result = ref<PromoSheetConfirmResult | null>(null);
const activeTab = ref("new");

function open(): void {
  phase.value = "select";
  selectedFile.value = null;
  progress.value = 0;
  preview.value = null;
  result.value = null;
  activeTab.value = "new";
}

function choose(): void {
  fileInput.value?.click();
}

function pick(event: Event): void {
  const files = (event.target as HTMLInputElement).files;
  const file = files?.[0];
  (event.target as HTMLInputElement).value = "";
  if (!file) return;
  if (!file.name.toLowerCase().endsWith(".xlsx")) {
    ElMessage.warning(t("promoSheet.xlsxOnly"));
    return;
  }
  if (file.size > PROMO_SHEET_MAX_BYTES) {
    ElMessage.warning(t("promoSheet.tooLarge"));
    return;
  }
  selectedFile.value = file;
  void upload(file);
}

async function upload(file: File): Promise<void> {
  if (!props.kolId) return;
  phase.value = "uploading";
  progress.value = 0;
  try {
    preview.value = await uploadPromoSheet(props.kolId, file, percent => {
      progress.value = percent;
    });
    phase.value = "preview";
  } catch (error) {
    notifyError(error);
    phase.value = "select";
    selectedFile.value = null;
  }
}

async function confirm(): Promise<void> {
  if (!props.kolId || !preview.value) return;
  phase.value = "confirming";
  try {
    result.value = await confirmPromoSheet(props.kolId, preview.value.uploadId);
    phase.value = "result";
    emit("confirmed");
  } catch (error) {
    notifyError(error);
    phase.value = "preview";
  }
}

const counts = computed(() => {
  const p = phase.value === "result" && result.value ? result.value : preview.value;
  if (!p) return [];
  const inserted = "inserted" in p ? (p as PromoSheetConfirmResult).inserted : p.toAdd;
  return [
    { key: "toAdd", label: t("promoSheet.toAdd"), value: inserted },
    { key: "existing", label: t("promoSheet.existing"), value: p.existing },
    { key: "conflicts", label: t("promoSheet.conflicts"), value: p.conflicts },
    { key: "duplicates", label: t("promoSheet.duplicates"), value: p.duplicates },
    { key: "notInSheet", label: t("promoSheet.notInSheet"), value: p.notInSheet },
    { key: "skipped", label: t("promoSheet.skipped"), value: skippedCount(p.skipCounts) }
  ];
});

function skippedCount(counts: Partial<Record<PromoSheetSkipReason, number>>): number {
  return Object.values(counts).reduce((sum, count) => sum + (count ?? 0), 0);
}

const skipRows = computed(() => preview.value?.skipSamples ?? []);

const skipReasonOptions: PromoSheetSkipReason[] = [
  "IN_SHEET",
  "NON_WEIDIAN",
  "NO_ITEM_ID",
  "SHOP_NOT_REGISTERED"
];

const newColumns = computed<DataColumn[]>(() => [
  { key: "shopName", label: t("promoSheet.shop"), prop: "shopName", minWidth: 110 },
  { key: "shopWeidianId", label: t("promoSheet.shopId"), minWidth: 110 },
  { key: "weidianItemId", label: t("mapping.exclusiveId"), minWidth: 150 },
  { key: "itemName", label: t("promoSheet.itemName"), minWidth: 160 },
  { key: "productCode", label: t("mapping.productCode"), minWidth: 130 }
]);

const conflictColumns = computed<DataColumn[]>(() => [
  { key: "shopWeidianId", label: t("promoSheet.shopId"), minWidth: 110 },
  { key: "weidianItemId", label: t("mapping.exclusiveId"), minWidth: 150 },
  { key: "itemName", label: t("promoSheet.itemName"), minWidth: 150 },
  { key: "owner", label: t("promoSheet.ownerKol"), minWidth: 180 }
]);

const skipColumns = computed<DataColumn[]>(() => [
  { key: "sheet", label: t("promoSheet.sheet"), minWidth: 130 },
  { key: "row", label: t("promoSheet.rowNumber"), width: 90 },
  { key: "reason", label: t("promoSheet.reason"), minWidth: 170 }
]);

function asNew(row: object) {
  return row as PromoSheetPreview["newItems"][number];
}

function asConflict(row: object) {
  return row as PromoSheetPreview["conflictsDetail"][number];
}

function asSkip(row: object) {
  return row as PromoSheetPreview["skipSamples"][number];
}

function ownerLabel(row: PromoSheetPreview["conflictsDetail"][number]): string {
  if (!row.ownerKolCode) return t("promoSheet.otherOwner");
  return `${row.ownerKolCode} ${row.ownerKolName ?? ""}`.trim();
}

function reasonLabel(reason: PromoSheetSkipReason): string {
  return t(`promoSheet.reasons.${reason}`);
}

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit("update:modelValue", value)
});
</script>

<template>
  <el-dialog v-model="dialogVisible" :title="t('promoSheet.title')" width="720px" @open="open">
    <div class="promo-sheet">
      <p class="target" data-test="promo-target">{{ kolLabel }}</p>

      <div v-if="phase === 'select'" class="select">
        <el-button type="primary" data-test="promo-choose" @click="choose">
          {{ t("promoSheet.chooseFile") }}
        </el-button>
        <p class="hint">{{ t("promoSheet.hint") }}</p>
        <input ref="fileInput" type="file" accept=".xlsx" class="file-input" @change="pick" />
      </div>

      <div v-else-if="phase === 'uploading'" class="uploading">
        <p>{{ selectedFile?.name }}</p>
        <el-progress :percentage="progress" data-test="promo-progress" />
      </div>

      <template v-else>
        <div class="count-cards" data-test="promo-counts">
          <div v-for="item in counts" :key="item.key" class="count-card">
            <span class="count-card__value">{{ item.value }}</span>
            <span class="count-card__label">{{ item.label }}</span>
          </div>
        </div>

        <el-tabs v-if="phase === 'preview'" v-model="activeTab">
          <el-tab-pane :label="t('promoSheet.tabNew')" name="new">
            <DataTable
              plain
              table-id="promo-new"
              :rows="preview?.newItems ?? []"
              :columns="newColumns"
              :total="preview?.newItems.length ?? 0"
              :paged="false"
            >
              <template #cell-productCode="{ row }">
                {{ asNew(row).productCode ?? "-" }}
              </template>
            </DataTable>
          </el-tab-pane>
          <el-tab-pane :label="t('promoSheet.tabConflicts')" name="conflicts">
            <el-alert
              v-if="!preview?.conflicts"
              :title="t('promoSheet.noConflicts')"
              type="success"
              :closable="false"
            />
            <DataTable
              v-else
              plain
              table-id="promo-conflicts"
              :rows="preview.conflictsDetail"
              :columns="conflictColumns"
              :total="preview.conflictsDetail.length"
              :paged="false"
            >
              <template #cell-owner="{ row }">{{ ownerLabel(asConflict(row)) }}</template>
            </DataTable>
          </el-tab-pane>
          <el-tab-pane :label="t('promoSheet.tabSkipped')" name="skipped">
            <ul class="skip-counts">
              <li v-for="reason in skipReasonOptions" :key="reason">
                {{ reasonLabel(reason) }}: {{ preview?.skipCounts[reason] ?? 0 }}
              </li>
            </ul>
            <DataTable
              plain
              table-id="promo-skips"
              :rows="skipRows"
              :columns="skipColumns"
              :total="skipRows.length"
              :paged="false"
            >
              <template #cell-reason="{ row }">{{ reasonLabel(asSkip(row).reason) }}</template>
            </DataTable>
          </el-tab-pane>
        </el-tabs>

        <div v-else class="result" data-test="promo-result">
          <el-alert type="success" :title="t('promoSheet.done')" :closable="false" />
        </div>
      </template>
    </div>

    <template #footer>
      <el-button @click="dialogVisible = false">{{ t("common.close") }}</el-button>
      <el-button
        v-if="phase === 'preview'"
        type="primary"
        data-test="promo-confirm"
        :disabled="!preview || preview.toAdd === 0"
        @click="confirm"
      >
        {{ t("promoSheet.confirm") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.promo-sheet {
  min-width: 0;
}
.target {
  margin: 0 0 12px;
  font-weight: 600;
}
.select {
  padding: 24px 0;
  text-align: center;
}
.hint {
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-body);
}
.file-input {
  display: none;
}
.uploading p {
  word-break: break-all;
}
.count-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(90px, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}
.count-card {
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  padding: 8px;
  text-align: center;
}
.count-card__value {
  display: block;
  font-size: var(--tcrm-font-size-section);
  font-weight: 600;
}
.count-card__label {
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
}
.skip-counts {
  margin: 0 0 8px;
  padding-left: 18px;
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-body);
}
</style>
