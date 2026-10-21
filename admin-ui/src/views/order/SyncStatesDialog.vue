<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import { listSyncFailures, listSyncStates, triggerOrderSync } from "@/api/order/order";
import type { OrderSyncStateVO, SyncDataType, SyncFailureVO, SyncStateRowVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { syncSourceText } from "@/utils/order";

defineOptions({ name: "SyncStatesDialog" });

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  /** At least one sync started; the page refreshes its list. */
  (event: "synced"): void;
}>();

const { t } = useI18n();
const router = useRouter();

/**
 * Task 40 §3.5: the sync monitor shows one row per shop and stream (order, refund) with its
 * source, last success, last error, 24h failures, missing-field count and import cutoff. The
 * failure tab lists recent sync and import failures with their import task.
 */
const activeTab = ref("states");
const loading = ref(false);
const shops = ref<OrderSyncStateVO[]>([]);
const syncing = ref<string | null>(null);
const failuresLoading = ref(false);
const failures = ref<SyncFailureVO[] | null>(null);
let startedAny = false;

/** One table row: a shop with one of its two monitoring rows. */
interface StateRow {
  shopId: string;
  shopName: string;
  enabled: boolean;
  authorized: boolean;
  row: SyncStateRowVO;
}

const stateRows = computed<StateRow[]>(() => {
  const list: StateRow[] = [];
  for (const shop of shops.value) {
    if (shop.rows?.length) {
      for (const row of shop.rows) {
        list.push({
          shopId: shop.shopId,
          shopName: shop.shopName,
          enabled: shop.enabled,
          authorized: shop.authorized,
          row
        });
      }
    } else {
      // Before the backend branch lands there are no per-stream rows; keep the old single row.
      list.push({
        shopId: shop.shopId,
        shopName: shop.shopName,
        enabled: shop.enabled,
        authorized: shop.authorized,
        row: {
          dataType: "ORDER",
          source: null,
          lastSuccessAt: shop.lastSuccessAt ?? null,
          lastError: shop.lastError ?? null,
          failures24h: 0,
          missingFieldCount: 0,
          lastImportCutoffTime: null
        }
      });
    }
  }
  return list;
});

function asState(row: object): StateRow {
  return row as StateRow;
}

function asFailure(row: object): SyncFailureVO {
  return row as SyncFailureVO;
}

function dataTypeText(type: SyncDataType): string {
  return t(`order.sync.type.${type}`);
}

const stateColumns = computed<DataColumn[]>(() => [
  { key: "shopName", label: t("order.syncShop"), prop: "shopName", minWidth: 150 },
  { key: "dataType", label: t("order.sync.typeColumn"), width: 80 },
  { key: "source", label: t("order.sync.source"), width: 100 },
  { key: "lastSuccessAt", label: t("order.syncLastSuccess"), width: 170 },
  { key: "lastError", label: t("order.syncLastError"), minWidth: 120 },
  { key: "failures24h", label: t("order.sync.failures24h"), width: 110 },
  { key: "missingFieldCount", label: t("order.sync.missingFields"), width: 110 },
  { key: "lastImportCutoffTime", label: t("order.sync.importCutoff"), width: 170 },
  { key: "actions", label: t("common.actions"), width: 100 }
]);

const failureColumns = computed<DataColumn[]>(() => [
  { key: "shopName", label: t("order.syncShop"), minWidth: 150 },
  { key: "dataType", label: t("order.sync.typeColumn"), width: 90 },
  { key: "time", label: t("order.sync.time"), width: 170 },
  { key: "error", label: t("order.syncLastError"), minWidth: 200, tooltip: true },
  { key: "task", label: t("order.sync.linkedTask"), width: 120 }
]);

async function load() {
  loading.value = true;
  try {
    shops.value = await listSyncStates();
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

async function loadFailures() {
  if (failures.value) return;
  failuresLoading.value = true;
  try {
    failures.value = await listSyncFailures();
  } catch (error) {
    notifyError(error);
  } finally {
    failuresLoading.value = false;
  }
}

watch(
  () => props.modelValue,
  open => {
    if (open) {
      startedAny = false;
      activeTab.value = "states";
      failures.value = null;
      void load();
    } else if (startedAny) {
      emit("synced");
    }
  },
  { immediate: true }
);

watch(activeTab, tab => {
  if (tab === "failures") void loadFailures();
});

async function syncNow(state: StateRow) {
  syncing.value = state.shopId;
  try {
    await triggerOrderSync(state.shopId);
    startedAny = true;
    ElMessage.success(t("order.syncStarted"));
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    syncing.value = null;
  }
}

function openTask(failure: SyncFailureVO) {
  if (!failure.taskId) return;
  void router.push({ path: "/system/tasks", query: { taskId: failure.taskId } });
}

function close() {
  emit("update:modelValue", false);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('order.syncStatus')"
    width="1160px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-tabs v-model="activeTab">
      <el-tab-pane :label="t('order.sync.statesTab')" name="states" lazy>
        <DataTable
          plain
          table-id="order-sync-states"
          :rows="stateRows"
          :columns="stateColumns"
          :total="stateRows.length"
          :paged="false"
          :loading="loading"
        >
          <template #cell-shopName="{ row }">
            <span data-test="sync-shop">{{ asState(row).shopName }}</span>
            <el-tag :type="asState(row).enabled ? 'success' : 'info'" size="small" class="shop-tag">
              {{ asState(row).enabled ? t("common.enabled") : t("common.disabled") }}
            </el-tag>
            <el-tag
              :type="asState(row).authorized ? 'success' : 'info'"
              size="small"
              class="shop-tag"
            >
              {{
                asState(row).authorized ? t("order.syncAuthorized") : t("order.syncUnauthorized")
              }}
            </el-tag>
          </template>
          <template #cell-dataType="{ row }">
            {{ dataTypeText(asState(row).row.dataType) }}
          </template>
          <template #cell-source="{ row }">
            {{ syncSourceText(asState(row).row.source) }}
          </template>
          <template #cell-lastSuccessAt="{ row }">
            {{ formatInstant(asState(row).row.lastSuccessAt) }}
          </template>
          <template #cell-lastError="{ row }">
            <el-tooltip
              v-if="asState(row).row.lastError"
              :content="asState(row).row.lastError ?? ''"
              placement="top"
            >
              <el-tag type="danger" data-test="sync-error">{{ t("order.syncFailed") }}</el-tag>
            </el-tooltip>
            <span v-else>-</span>
          </template>
          <template #cell-failures24h="{ row }">{{ asState(row).row.failures24h }}</template>
          <template #cell-missingFieldCount="{ row }">
            {{ asState(row).row.missingFieldCount }}
          </template>
          <template #cell-lastImportCutoffTime="{ row }">
            {{ formatInstant(asState(row).row.lastImportCutoffTime) }}
          </template>
          <template #actions="{ row }">
            <el-button
              v-if="
                asState(row).row.dataType === 'ORDER' &&
                asState(row).enabled &&
                asState(row).authorized
              "
              link
              type="primary"
              size="small"
              data-test="sync-now"
              :loading="syncing === asState(row).shopId"
              @click="syncNow(asState(row))"
            >
              {{ t("order.syncNow") }}
            </el-button>
            <span v-else>-</span>
          </template>
        </DataTable>
      </el-tab-pane>
      <el-tab-pane :label="t('order.sync.failuresTab')" name="failures" lazy>
        <DataTable
          plain
          table-id="order-sync-failures"
          :rows="failures ?? []"
          :columns="failureColumns"
          :total="(failures ?? []).length"
          :paged="false"
          :loading="failuresLoading"
        >
          <template #cell-shopName="{ row }">{{ asFailure(row).shopName ?? "-" }}</template>
          <template #cell-dataType="{ row }">{{ dataTypeText(asFailure(row).dataType) }}</template>
          <template #cell-time="{ row }">{{ formatInstant(asFailure(row).time) }}</template>
          <template #cell-error="{ row }">{{ asFailure(row).error ?? "-" }}</template>
          <template #cell-task="{ row }">
            <el-button
              v-if="asFailure(row).taskId"
              link
              type="primary"
              data-test="failure-task"
              @click="openTask(asFailure(row))"
            >
              {{ t("order.sync.viewTask") }}
            </el-button>
            <span v-else>-</span>
          </template>
        </DataTable>
      </el-tab-pane>
    </el-tabs>
    <template #footer>
      <el-button @click="close">{{ t("common.close") }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.shop-tag {
  margin-left: 4px;
}
</style>
