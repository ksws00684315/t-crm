<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import {
  deleteSyncFailures,
  listSyncFailures,
  listSyncStates,
  triggerOrderSync
} from "@/api/order/order";
import { listShops } from "@/api/product/shop";
import type {
  KindSyncVO,
  OrderSyncStateVO,
  SyncDataType,
  SyncFailureKind,
  SyncFailureVO
} from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { orderAbilities } from "@/utils/permission";
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
const abilities = computed(() => orderAbilities(useUserStore().user));

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
const cleaning = ref(false);
let startedAny = false;

/** One table row: a shop with one of its two monitoring streams. */
interface StateRow {
  shopId: string;
  shopName: string;
  enabled: boolean;
  authorized: boolean;
  dataType: SyncDataType;
  kind: KindSyncVO | null;
  /** The shop-level import cutoff, shown on the stream it belongs to. */
  importCutoffTime: string | null;
}

/** The spec shows one count; the backend reports per-field counts (design §4.6a). */
function missingFieldCount(kind: KindSyncVO | null): number {
  return kind
    ? Object.values(kind.missingFields ?? {}).reduce<number>((sum, count) => sum + Number(count), 0)
    : 0;
}

const stateRows = computed<StateRow[]>(() => {
  const list: StateRow[] = [];
  for (const shop of shops.value) {
    for (const dataType of ["ORDER", "REFUND"] as const) {
      list.push({
        shopId: shop.shopId,
        shopName: shop.shopName,
        enabled: shop.enabled,
        authorized: shop.authorized,
        dataType,
        kind: (dataType === "ORDER" ? shop.orders : shop.refunds) ?? null,
        importCutoffTime:
          shop.lastImport && shop.lastImport.kind === dataType
            ? shop.lastImport.dataCutoffTime
            : null
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
  return t(`order.sync.typeOption.${type}`);
}

function failureKindText(kind: SyncFailureKind): string {
  return t(`order.sync.kindOption.${kind}`);
}

const stateColumns = computed<DataColumn[]>(() => [
  {
    key: "shopName",
    align: "left" as const,
    label: t("order.syncShop"),
    prop: "shopName",
    minWidth: 150
  },
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
  { key: "shopName", align: "left" as const, label: t("order.syncShop"), minWidth: 150 },
  { key: "kind", label: t("order.sync.typeColumn"), width: 110 },
  { key: "time", label: t("order.sync.time"), width: 170 },
  {
    key: "error",
    align: "left" as const,
    label: t("order.syncLastError"),
    minWidth: 200,
    tooltip: true
  },
  { key: "task", align: "left" as const, label: t("order.sync.linkedTask"), width: 120 }
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
    // L-003: show the failure shops as "name (code)". The failure VO carries no code, so map it
    // from the shop options; without product:view (or on failure) the plain name stays.
    const codes = new Map<string, string>();
    try {
      for (const shop of await listShops()) {
        if (shop.shopCode) codes.set(shop.id, shop.shopCode);
      }
      shopCodes.value = codes;
    } catch {
      shopCodes.value = new Map();
    }
  } catch (error) {
    notifyError(error);
  } finally {
    failuresLoading.value = false;
  }
}

/** Shop codes for the "name (code)" display on the failures tab (L-003). */
const shopCodes = ref(new Map<string, string>());

function failureShopText(failure: SyncFailureVO): string {
  if (!failure.shopName && !failure.shopId) return "-";
  const code = failure.shopId ? shopCodes.value.get(failure.shopId) : undefined;
  return code ? formatNameWithCode(failure.shopName ?? "-", code) : (failure.shopName ?? "-");
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

async function cleanFailures() {
  let days: number;
  try {
    const { value } = await ElMessageBox.prompt(
      t("order.sync.cleanFailuresPrompt"),
      t("order.sync.cleanFailuresTitle"),
      {
        inputValue: "30",
        inputPattern: /^[1-9]\d*$/,
        inputErrorMessage: t("order.sync.cleanFailuresDaysInvalid"),
        confirmButtonText: t("common.confirm"),
        cancelButtonText: t("common.cancel")
      }
    );
    days = Number(value);
    if (!Number.isInteger(days) || days < 1 || days > 3650) {
      ElMessage.error(t("order.sync.cleanFailuresDaysInvalid"));
      return;
    }
  } catch {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("order.sync.cleanFailuresConfirm", { days }),
      t("order.sync.cleanFailuresTitle"),
      { type: "warning" }
    );
  } catch {
    return;
  }
  cleaning.value = true;
  try {
    const count = await deleteSyncFailures(days);
    ElMessage.success(t("order.sync.cleanFailuresDone", { count }));
    failures.value = null;
    await loadFailures();
  } catch (error) {
    notifyError(error);
  } finally {
    cleaning.value = false;
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
            {{ dataTypeText(asState(row).dataType) }}
          </template>
          <template #cell-source="{ row }">
            {{ syncSourceText(asState(row).kind?.source) }}
          </template>
          <template #cell-lastSuccessAt="{ row }">
            {{ formatInstant(asState(row).kind?.lastSuccessAt) }}
          </template>
          <template #cell-lastError="{ row }">
            <el-tooltip
              v-if="asState(row).kind?.lastError"
              :content="asState(row).kind?.lastError ?? ''"
              placement="top"
            >
              <el-tag type="danger" data-test="sync-error">{{ t("order.syncFailed") }}</el-tag>
            </el-tooltip>
            <span v-else>-</span>
          </template>
          <template #cell-failures24h="{ row }">{{ asState(row).kind?.failures24h ?? 0 }}</template>
          <template #cell-missingFieldCount="{ row }">
            {{ missingFieldCount(asState(row).kind) }}
          </template>
          <template #cell-lastImportCutoffTime="{ row }">
            {{ formatInstant(asState(row).importCutoffTime) }}
          </template>
          <template #actions="{ row }">
            <el-button
              v-if="
                asState(row).dataType === 'ORDER' && asState(row).enabled && asState(row).authorized
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
        <div v-if="abilities.canManage" class="failures-toolbar">
          <el-button
            type="danger"
            plain
            data-test="clean-failures"
            :loading="cleaning"
            @click="cleanFailures"
          >
            {{ t("order.sync.cleanFailures") }}
          </el-button>
        </div>
        <DataTable
          plain
          table-id="order-sync-failures"
          :rows="failures ?? []"
          :columns="failureColumns"
          :total="(failures ?? []).length"
          :paged="false"
          :loading="failuresLoading"
        >
          <template #cell-shopName="{ row }">{{ failureShopText(asFailure(row)) }}</template>
          <template #cell-kind="{ row }">{{ failureKindText(asFailure(row).kind) }}</template>
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
.failures-toolbar {
  margin-bottom: 12px;
}
</style>
