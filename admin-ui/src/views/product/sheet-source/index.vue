<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { getGoogleCredential, saveGoogleCredential } from "@/api/integration/google";
import {
  deleteSheetSource,
  listSheetSources,
  updateSheetSourceStatus
} from "@/api/product/sheet-source";
import { listShops } from "@/api/product/shop";
import type { GoogleCredentialVO, SheetSourceVO, ShopVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import { messageForErrorCode } from "@/api/error-codes";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { productAbilities } from "@/utils/permission";
import MissingProductsDrawer from "./MissingProductsDrawer.vue";
import SheetReadDialog from "./SheetReadDialog.vue";
import SheetSourceFormDialog from "./SheetSourceFormDialog.vue";
import SkippedRows from "./SkippedRows.vue";

defineOptions({ name: "SheetSourcePage" });

const { t } = useI18n();
const me = useUserStore();
const abilities = computed(() => productAbilities(me.user));
/** Configuration and the service account are super-administrator only (ADR-021 §10). */
const canConfigure = computed(() => abilities.value.canManageShop);
const canRead = computed(() => abilities.value.canEditProduct);
const KEY_FILE_MAX_BYTES = 8 * 1024;

const loading = ref(false);
const rows = ref<SheetSourceVO[]>([]);
const shops = ref<ShopVO[]>([]);
const credential = ref<GoogleCredentialVO | null>(null);
const keyInput = ref<HTMLInputElement>();
const savingKey = ref(false);
const formVisible = ref(false);
const editing = ref<SheetSourceVO | null>(null);
const readVisible = ref(false);
const missingVisible = ref(false);
const selected = ref<SheetSourceVO | null>(null);

function asSource(row: object): SheetSourceVO {
  return row as SheetSourceVO;
}

const columns = computed<DataColumn[]>(() => {
  const list: DataColumn[] = [
    { key: "sourceName", label: t("sheetSource.name"), prop: "sourceName", minWidth: 110 },
    { key: "sheet", label: t("sheetSource.sheet"), minWidth: 120 },
    { key: "columns", label: t("sheetSource.columns"), minWidth: 150 },
    { key: "defaultShop", label: t("sheetSource.defaultShop"), width: 100 },
    { key: "currency", label: t("sheetSource.currency"), prop: "priceCurrency", width: 80 },
    { key: "status", label: t("common.status"), width: 80 },
    { key: "lastRead", label: t("sheetSource.lastRead"), minWidth: 220 }
  ];
  if (canRead.value || canConfigure.value) {
    list.push({ key: "actions", label: t("common.actions"), width: 220 });
  }
  return list;
});

function sheetUrl(row: SheetSourceVO): string {
  return `https://docs.google.com/spreadsheets/d/${row.spreadsheetId}`;
}

function skippedCount(row: SheetSourceVO): number {
  const s = row.lastSkipped;
  return s ? s.nonWeidian + s.noItemId + s.noShop : 0;
}

async function load() {
  loading.value = true;
  try {
    rows.value = await listSheetSources();
    if (canConfigure.value) {
      [credential.value, shops.value] = await Promise.all([getGoogleCredential(), listShops()]);
    }
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

async function onKeyFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) return;
  if (file.size > KEY_FILE_MAX_BYTES || !file.name.toLowerCase().endsWith(".json")) {
    ElMessage.error(t("sheetSource.googleFileInvalid"));
    return;
  }
  savingKey.value = true;
  try {
    credential.value = await saveGoogleCredential(await file.text());
    ElMessage.success(t("sheetSource.googleSaved"));
  } catch (error) {
    notifyError(error);
  } finally {
    savingKey.value = false;
  }
}

function openCreate() {
  editing.value = null;
  formVisible.value = true;
}

function openEdit(row: SheetSourceVO) {
  editing.value = row;
  formVisible.value = true;
}

function openRead(row: SheetSourceVO) {
  selected.value = row;
  readVisible.value = true;
}

function openMissing(row: SheetSourceVO) {
  selected.value = row;
  missingVisible.value = true;
}

async function toggleStatus(row: SheetSourceVO) {
  const disabling = row.status === 1;
  try {
    await ElMessageBox.confirm(
      disabling
        ? t("sheetSource.confirmDisable", { name: row.sourceName })
        : t("sheetSource.confirmEnable", { name: row.sourceName }),
      { type: "warning" }
    );
  } catch {
    return;
  }
  try {
    await updateSheetSourceStatus(row.id, disabling ? 0 : 1);
    ElMessage.success(disabling ? t("common.disabled") : t("common.enabled"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

async function remove(row: SheetSourceVO) {
  try {
    await ElMessageBox.confirm(t("sheetSource.confirmDelete", { name: row.sourceName }), {
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    await deleteSheetSource(row.id);
    ElMessage.success(t("sheetSource.deleted"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

onMounted(() => void load());
</script>

<template>
  <div class="list-page">
    <el-card v-if="canConfigure" shadow="never" class="google-card" data-test="google-card">
      <div class="google-row">
        <strong>{{ t("sheetSource.google") }}</strong>
        <template v-if="credential?.configured">
          <CopyText :value="credential.clientEmail ?? ''" />
          <span class="hint">
            {{ t("sheetSource.googleShareHint", { email: credential.clientEmail }) }}
          </span>
        </template>
        <span v-else class="hint">{{ t("sheetSource.googleNotConfigured") }}</span>
        <el-button :loading="savingKey" data-test="google-upload" @click="keyInput?.click()">
          {{
            credential?.configured ? t("sheetSource.googleReplace") : t("sheetSource.googleUpload")
          }}
        </el-button>
        <input
          ref="keyInput"
          type="file"
          accept=".json,application/json"
          class="hidden-input"
          data-test="google-file"
          @change="onKeyFile"
        />
      </div>
    </el-card>

    <ListSearch @search="load" @reset="load">
      <template #primary>
        <span class="hint">{{ t("sheetSource.hint") }}</span>
      </template>
      <template v-if="canConfigure" #actions>
        <el-button type="primary" data-test="create" @click="openCreate">
          {{ t("sheetSource.create") }}
        </el-button>
      </template>
    </ListSearch>

    <DataTable
      table-id="sheet-sources"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="rows.length"
      :paged="false"
      @refresh="load"
    >
      <template #cell-sheet="{ row }">
        <el-link
          :href="sheetUrl(asSource(row))"
          target="_blank"
          rel="noopener noreferrer"
          type="primary"
        >
          {{ asSource(row).sheetTitle }}
        </el-link>
      </template>
      <template #cell-columns="{ row }">
        {{
          t("sheetSource.columnsSummary", {
            link: asSource(row).linkColumn,
            name: asSource(row).nameColumn,
            price: asSource(row).priceColumn
          })
        }}
      </template>
      <template #cell-defaultShop="{ row }">{{ asSource(row).defaultShopName ?? "-" }}</template>
      <template #cell-status="{ row }">
        <el-tag :type="asSource(row).status === 1 ? 'success' : 'info'">
          {{ asSource(row).status === 1 ? t("common.enabled") : t("common.disabled") }}
        </el-tag>
      </template>
      <template #cell-lastRead="{ row }">
        <div v-if="asSource(row).lastError" class="error" data-test="last-error">
          {{ messageForErrorCode(asSource(row).lastError!) ?? asSource(row).lastError }}
        </div>
        <div v-if="asSource(row).lastReadAt">
          {{
            t("sheetSource.lastReadSummary", {
              time: formatInstant(asSource(row).lastReadAt),
              rows: asSource(row).lastRowCount ?? 0
            })
          }}
          <el-popover v-if="skippedCount(asSource(row)) > 0" width="520" trigger="click">
            <template #reference>
              <el-button link type="warning" data-test="skipped-link">
                {{ t("sheetSource.lastSkippedSummary", { count: skippedCount(asSource(row)) }) }}
              </el-button>
            </template>
            <SkippedRows :skipped="asSource(row).lastSkipped!" />
          </el-popover>
        </div>
        <span v-else-if="!asSource(row).lastError" class="hint">
          {{ t("sheetSource.neverRead") }}
        </span>
      </template>
      <template #actions="{ row }">
        <el-button
          v-if="canRead"
          link
          type="primary"
          :disabled="asSource(row).status !== 1"
          data-test="read"
          @click="openRead(asSource(row))"
        >
          {{ t("sheetSource.read") }}
        </el-button>
        <el-button
          v-if="canRead"
          link
          type="primary"
          data-test="missing"
          @click="openMissing(asSource(row))"
        >
          {{ t("sheetSource.missing") }}
        </el-button>
        <template v-if="canConfigure">
          <el-button link type="primary" data-test="edit" @click="openEdit(asSource(row))">
            {{ t("common.edit") }}
          </el-button>
          <el-button
            link
            :type="asSource(row).status === 1 ? 'danger' : 'success'"
            data-test="status"
            @click="toggleStatus(asSource(row))"
          >
            {{ asSource(row).status === 1 ? t("common.disable") : t("common.enable") }}
          </el-button>
          <el-button link type="danger" data-test="delete" @click="remove(asSource(row))">
            {{ t("common.delete") }}
          </el-button>
        </template>
      </template>
    </DataTable>

    <SheetSourceFormDialog v-model="formVisible" :source="editing" :shops="shops" @saved="load" />
    <SheetReadDialog v-model="readVisible" :source="selected" @finished="load" />
    <MissingProductsDrawer v-model="missingVisible" :source="selected" />
  </div>
</template>

<style scoped>
.google-card {
  margin-bottom: 12px;
}

.google-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.hint {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}

.error {
  color: var(--el-color-danger);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}

.hidden-input {
  display: none;
}
</style>
