<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { listSyncStates, triggerOrderSync } from "@/api/order/order";
import type { OrderSyncStateVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "SyncStatesDialog" });

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  /** At least one sync started; the page refreshes its list. */
  (event: "synced"): void;
}>();

const { t } = useI18n();
const loading = ref(false);
const rows = ref<OrderSyncStateVO[]>([]);
const syncing = ref<string | null>(null);
let startedAny = false;

function asState(row: object): OrderSyncStateVO {
  return row as OrderSyncStateVO;
}

const columns = computed<DataColumn[]>(() => [
  { key: "shopName", label: t("order.syncShop"), prop: "shopName", minWidth: 140 },
  { key: "enabled", label: t("order.syncEnabled"), width: 90 },
  { key: "authorized", label: t("order.syncAuthorized"), width: 90 },
  { key: "lastSuccessAt", label: t("order.syncLastSuccess"), width: 170, mono: true },
  { key: "lastError", label: t("order.syncLastError"), minWidth: 120 },
  { key: "actions", label: t("common.actions"), width: 110 }
]);

async function load() {
  loading.value = true;
  try {
    rows.value = await listSyncStates();
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.modelValue,
  open => {
    if (open) {
      startedAny = false;
      void load();
    } else if (startedAny) {
      emit("synced");
    }
  }
);

async function syncNow(state: OrderSyncStateVO) {
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

function close() {
  emit("update:modelValue", false);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('order.syncStatus')"
    width="860px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <DataTable
      plain
      table-id="order-sync-states"
      :rows="rows"
      :columns="columns"
      :total="rows.length"
      :paged="false"
      :loading="loading"
    >
      <template #cell-enabled="{ row }">
        <el-tag :type="asState(row).enabled ? 'success' : 'info'">
          {{ asState(row).enabled ? t("common.enabled") : t("common.disabled") }}
        </el-tag>
      </template>
      <template #cell-authorized="{ row }">
        <el-tag :type="asState(row).authorized ? 'success' : 'info'">
          {{ asState(row).authorized ? t("order.syncAuthorized") : t("order.syncUnauthorized") }}
        </el-tag>
      </template>
      <template #cell-lastSuccessAt="{ row }">
        {{ formatInstant(asState(row).lastSuccessAt) }}
      </template>
      <template #cell-lastError="{ row }">
        <el-tooltip
          v-if="asState(row).lastError"
          :content="asState(row).lastError ?? ''"
          placement="top"
        >
          <el-tag type="danger" data-test="sync-error">{{ t("order.syncFailed") }}</el-tag>
        </el-tooltip>
        <span v-else>-</span>
      </template>
      <template #actions="{ row }">
        <el-button
          v-if="asState(row).enabled && asState(row).authorized"
          link
          type="primary"
          size="small"
          data-test="sync-now"
          :loading="syncing === asState(row).shopId"
          @click="syncNow(asState(row))"
        >
          {{ t("order.syncNow") }}
        </el-button>
      </template>
    </DataTable>
    <template #footer>
      <el-button @click="close">{{ t("common.close") }}</el-button>
    </template>
  </el-dialog>
</template>
