<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { listPayoutAccounts, type PayoutAccountRowVO } from "@/api/commission/payout-account";
import type { KolVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { formatInstant, formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import PayoutAccountDetailDrawer from "./PayoutAccountDetailDrawer.vue";

defineOptions({ name: "KolFundDialog" });

/**
 * Read-only payout accounts of one creator (task 82): the list shows masked values, and the full
 * account opens the detail drawer. Creators add their own accounts; there is no admin write here.
 */
const props = defineProps<{
  modelValue: boolean;
  kol: KolVO | null;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
}>();

const { t } = useI18n();
const loading = ref(false);
const rows = ref<PayoutAccountRowVO[]>([]);
const detailVisible = ref(false);
const detailId = ref<string | null>(null);

const title = computed(() =>
  props.kol
    ? `${t("kol.actionFund")} - ${formatNameWithCode(props.kol.name, props.kol.kolCode)}`
    : t("kol.actionFund")
);

const columns = computed<DataColumn[]>(() => [
  { key: "type", align: "left" as const, label: t("kol.fundType"), minWidth: 90 },
  { key: "account", align: "left" as const, label: t("kol.fundAccount"), minWidth: 160 },
  { key: "recipient", align: "left" as const, label: t("kol.fundRecipient"), minWidth: 120 },
  { key: "currency", label: t("kol.fundCurrency"), minWidth: 80 },
  { key: "default", label: t("kol.fundDefault"), minWidth: 72 },
  { key: "status", label: t("kol.fundStatus"), minWidth: 90 },
  { key: "updated", label: t("kol.fundUpdatedAt"), minWidth: 150 },
  { key: "actions", label: t("common.actions"), minWidth: 110 }
]);

watch(
  () => [props.modelValue, props.kol?.id] as const,
  async ([open, kolId]) => {
    if (!open || !kolId) return;
    await load(kolId);
  },
  { immediate: true }
);

async function load(kolId: string): Promise<void> {
  loading.value = true;
  try {
    // Disabled accounts are listed too (no status filter); a creator has few accounts.
    const page = await listPayoutAccounts({ kolId, page: 1, size: 100 });
    rows.value = page.items;
  } catch (error) {
    notifyError(error);
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

function asRow(row: object): PayoutAccountRowVO {
  return row as PayoutAccountRowVO;
}

function typeText(row: PayoutAccountRowVO): string {
  return t(`payout.typeOption.${row.accountType}`);
}

function recipientText(row: PayoutAccountRowVO): string {
  const value = row.accountType === "PAYPAL" ? row.holderName : row.network;
  return value || "-";
}

function currencyText(row: PayoutAccountRowVO): string {
  if (row.accountType === "USDT") return "USDT";
  return row.currency || "-";
}

function openDetail(row: PayoutAccountRowVO): void {
  detailId.value = row.id;
  detailVisible.value = true;
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="min(960px, calc(100vw - 24px))"
    :close-on-click-modal="false"
    data-test="kol-fund-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-empty
      v-if="!loading && rows.length === 0"
      :description="t('kol.fundEmpty')"
      data-test="fund-empty"
    />
    <DataTable
      v-else
      table-id="kol-fund"
      plain
      :paged="false"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="rows.length"
    >
      <template #cell-type="{ row }">{{ typeText(asRow(row)) }}</template>
      <template #cell-account="{ row }">
        <span data-test="fund-account-masked">{{ asRow(row).accountMasked }}</span>
      </template>
      <template #cell-recipient="{ row }">{{ recipientText(asRow(row)) }}</template>
      <template #cell-currency="{ row }">{{ currencyText(asRow(row)) }}</template>
      <template #cell-default="{ row }">
        <el-tag v-if="asRow(row).defaultAccount" type="success">{{ t("kol.fundDefault") }}</el-tag>
        <span v-else>-</span>
      </template>
      <template #cell-status="{ row }">
        <el-tag
          :type="asRow(row).status === 'DISABLED' ? 'info' : 'success'"
          data-test="fund-status"
        >
          {{ asRow(row).status === "DISABLED" ? t("kol.fundDisabled") : t("kol.fundActive") }}
        </el-tag>
      </template>
      <template #cell-updated="{ row }">{{ formatInstant(asRow(row).lastSubmittedAt) }}</template>
      <template #actions="{ row }">
        <el-button
          link
          type="primary"
          :data-test="`fund-view-${asRow(row).id}`"
          @click="openDetail(asRow(row))"
          >{{ t("kol.fundViewFull") }}</el-button
        >
      </template>
    </DataTable>
    <PayoutAccountDetailDrawer v-model="detailVisible" :account-id="detailId" />
  </el-dialog>
</template>
