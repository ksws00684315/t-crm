<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  listPrepays,
  getPrepay,
  prepayLedger,
  downloadPrepayVoucher,
  type PrepayResult,
  type PrepayEntry
} from "@/api/prepay";
import { useSubmission } from "@/utils/useSubmission";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { formatInstant } from "@/utils/format";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "PrepayResultsPage" });
const { t, locale } = useI18n();
const tab = ref("payments");
const rows = ref<PrepayResult[]>([]);
const ledger = ref<PrepayEntry[]>([]);
const page = ref(1);
const total = ref(0);
const tableId = computed(() => `kol-prepay-${tab.value}`);
const size = ref(readPageSize(tableId.value, 20));
const detail = ref<PrepayResult | null>(null);
const detailVisible = ref(false);
const { busy, errorText, submit } = useSubmission();
const downloadState = useSubmission();
let generation = 0;
const columns = computed<DataColumn[]>(() => {
  const english = locale.value === "en-US";
  const number = {
    key: "prepayNo",
    align: "left" as const,
    label: t("prepay.number"),
    minWidth: 180
  };
  if (tab.value === "ledger")
    return [
      number,
      {
        key: "entryType",
        label: t("prepay.entryType"),
        minWidth: english ? 240 : 200,
        tooltip: true
      },
      { key: "amountCny", label: t("prepay.entryAmount"), minWidth: 150, amount: true },
      { key: "occurredAt", label: t("prepay.occurredAt"), minWidth: 190 },
      {
        key: "balanceAfterCny",
        label: t("prepay.balanceAfter"),
        minWidth: english ? 250 : 200,
        amount: true
      }
    ];
  return [
    number,
    { key: "payAmountCny", label: t("prepay.paidAmount"), minWidth: 150, amount: true },
    { key: "payTime", label: t("prepay.payTime"), minWidth: 190 },
    { key: "payMethod", label: t("prepay.payMethod"), minWidth: 150, tooltip: true },
    {
      key: "walletBalanceCny",
      label: t("prepay.balance"),
      minWidth: english ? 210 : 150,
      amount: true
    },
    { key: "recovered", label: t("prepay.recovered"), minWidth: 140, amount: true },
    { key: "remaining", label: t("prepay.remaining"), minWidth: english ? 180 : 140, amount: true },
    { key: "actions", label: t("common.actions"), width: english ? 210 : 160 }
  ];
});
function paymentActions(row: PrepayResult): RowAction[] {
  if (!row.id) return [];
  return [
    {
      key: "view",
      label: t("prepay.detail"),
      testId: "prepay-result-detail",
      disabled: downloadState.busy.value,
      onClick: () => void open(row)
    },
    {
      key: "voucher",
      label: t("prepay.voucher"),
      testId: "prepay-voucher-download",
      disabled: downloadState.busy.value,
      onClick: () => void download(row.voucherFileId)
    }
  ];
}
async function load() {
  if (busy.value) return;
  const request = ++generation;
  await submit(async () => {
    rows.value = [];
    ledger.value = [];
    total.value = 0;
    if (tab.value === "payments") {
      const result = await listPrepays(page.value, size.value);
      if (request === generation) {
        rows.value = result.items;
        total.value = Number(result.total);
      }
    } else {
      const result = await prepayLedger(page.value, size.value);
      if (request === generation) {
        ledger.value = result.items;
        total.value = Number(result.total);
      }
    }
  });
}
async function open(row: PrepayResult) {
  detail.value = null;
  detailVisible.value = true;
  await downloadState.submit(async () => {
    detail.value = await getPrepay(row.id);
  });
}
async function download(id: string) {
  await downloadState.submit(async () => {
    const file = await downloadPrepayVoucher(id);
    const url = URL.createObjectURL(file.blob);
    const link = document.createElement("a");
    link.href = url;
    const extension =
      { "image/png": ".png", "image/jpeg": ".jpg", "application/pdf": ".pdf" }[file.blob.type] ??
      "";
    link.download = `${t("prepay.voucher")}${extension}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  });
}
function turn(value: number) {
  if (busy.value) return;
  page.value = value;
  void load();
}
function changeSize(value: number) {
  size.value = value;
}
watch(tab, () => {
  page.value = 1;
  size.value = readPageSize(tableId.value, 20);
  void load();
});
onMounted(load);
</script>
<template>
  <main class="prepay-results" data-test="prepay-results">
    <el-alert
      v-if="errorText"
      :title="errorText"
      type="error"
      :closable="false"
      data-test="prepay-error"
    />
    <el-alert
      v-if="downloadState.errorText.value"
      :title="downloadState.errorText.value"
      type="error"
      :closable="false"
    />
    <DataTable
      :key="tab"
      :table-id="tableId"
      :rows="tab === 'payments' ? rows : ledger"
      :row-key="tab === 'payments' ? 'id' : 'entryId'"
      :columns="columns"
      :loading="busy"
      :total="total"
      :page="page"
      :size="size"
      @update:page="turn"
      @update:size="changeSize"
      @refresh="load"
    >
      <template #title>
        <h1>{{ t("prepay.title") }}</h1>
        <p>{{ t("prepay.paidOnly") }}</p>
      </template>
      <template #toolbar>
        <el-radio-group
          v-model="tab"
          :disabled="busy"
          :aria-label="t('prepay.title')"
          data-test="prepay-tabs"
        >
          <el-radio-button value="payments">{{ t("prepay.payments") }}</el-radio-button>
          <el-radio-button value="ledger">{{ t("prepay.ledger") }}</el-radio-button>
        </el-radio-group>
      </template>
      <template #cell-prepayNo="{ row }"><CopyText :value="row.prepayNo" /></template>
      <template #cell-payAmountCny="{ row }">{{ row.payAmountCny }} CNY</template>
      <template #cell-payTime="{ row }">{{ formatInstant(row.payTime) }}</template>
      <template #cell-walletBalanceCny="{ row }">{{ row.walletBalanceCny }} CNY</template>
      <template #cell-recovered="{ row }">
        <template v-if="row.deduction">{{ row.deduction.recoveredAmountCny }} CNY</template>
        <span v-else :title="!row.deductFromCommission ? t('prepay.noDeduction') : undefined"
          >—</span
        >
      </template>
      <template #cell-remaining="{ row }">
        <template v-if="row.deduction">{{ row.deduction.remainingAmountCny }} CNY</template>
        <span v-else :title="!row.deductFromCommission ? t('prepay.noDeduction') : undefined"
          >—</span
        >
      </template>
      <template #actions="{ row }"><RowActions :actions="paymentActions(row)" /></template>
      <template #cell-entryType="{ row }">{{
        row.entryType ? t(`prepay.entry.${row.entryType}`) : "—"
      }}</template>
      <template #cell-amountCny="{ row }">{{ row.amountCny }} CNY</template>
      <template #cell-occurredAt="{ row }">{{ formatInstant(row.occurredAt) }}</template>
      <template #cell-balanceAfterCny="{ row }">
        <template v-if="row.balanceAfterCny != null">{{ row.balanceAfterCny }} CNY</template>
        <template v-else-if="row.entryType === 'PREPAY_PAID'">{{
          t("prepay.walletUnchanged")
        }}</template>
        <template v-else>—</template>
      </template>
      <template #empty><el-empty :description="t('prepay.empty')" /></template>
    </DataTable>
    <!-- Preserve the existing small-screen cards; desktop rows and paging belong to DataTable. -->
    <section v-loading="busy" class="prepay-mobile">
      <el-empty
        v-if="!busy && !(tab === 'payments' ? rows.length : ledger.length)"
        :description="t('prepay.empty')"
      />
      <template v-if="tab === 'payments'">
        <article
          v-for="row in rows"
          :key="row.id"
          class="prepay-record"
          data-test="prepay-result-row"
        >
          <h2><CopyText :value="row.prepayNo" /></h2>
          <dl>
            <dt>{{ t("prepay.paidAmount") }}</dt>
            <dd>{{ row.payAmountCny }} CNY</dd>
            <dt>{{ t("prepay.payTime") }}</dt>
            <dd>{{ formatInstant(row.payTime) }}</dd>
            <dt>{{ t("prepay.payMethod") }}</dt>
            <dd>{{ row.payMethod }}</dd>
            <dt>{{ t("prepay.balance") }}</dt>
            <dd data-test="prepay-wallet">{{ row.walletBalanceCny }} CNY</dd>
          </dl>
          <p v-if="row.deduction">
            {{ t("prepay.recovered") }} {{ row.deduction.recoveredAmountCny }} CNY ·
            {{ t("prepay.remaining") }} {{ row.deduction.remainingAmountCny }} CNY
          </p>
          <p v-else-if="!row.deductFromCommission">{{ t("prepay.noDeduction") }}</p>
          <RowActions :actions="paymentActions(row)" />
        </article>
      </template>
      <template v-else
        ><article
          v-for="entry in ledger"
          :key="entry.entryId"
          class="prepay-record"
          data-test="prepay-ledger-row"
        >
          <h2><CopyText :value="entry.prepayNo" /></h2>
          <p>{{ t(`prepay.entry.${entry.entryType}`) }} · {{ entry.amountCny }} CNY</p>
          <p>{{ formatInstant(entry.occurredAt) }}</p>
          <p v-if="entry.balanceAfterCny != null">
            {{ t("prepay.balanceAfter") }} {{ entry.balanceAfterCny }} CNY
          </p>
          <p v-else-if="entry.entryType === 'PREPAY_PAID'">{{ t("prepay.walletUnchanged") }}</p>
        </article></template
      >
      <el-pagination
        :current-page="page"
        :page-size="size"
        :total="total"
        :disabled="busy"
        layout="prev, pager, next"
        data-test="prepay-pagination"
        @current-change="turn"
      />
    </section>
    <el-dialog
      v-model="detailVisible"
      :title="t('prepay.detail')"
      width="min(640px, 95vw)"
      @closed="detail = null"
    >
      <div v-loading="downloadState.busy.value" data-test="prepay-paid-detail">
        <template v-if="detail"
          ><h2><CopyText :value="detail.prepayNo" /></h2>
          <p>
            {{ detail.payAmountCny }} CNY · {{ detail.payMethod }} ·
            {{ formatInstant(detail.payTime) }}
          </p>
          <p>{{ t("prepay.balance") }} {{ detail.walletBalanceCny }} CNY</p>
          <p v-if="detail.deduction">
            {{ t("prepay.posted") }} {{ detail.deduction.postedAmountCny }} CNY ·
            {{ t("prepay.recovered") }} {{ detail.deduction.recoveredAmountCny }} CNY ·
            {{ t("prepay.remaining") }} {{ detail.deduction.remainingAmountCny }} CNY
          </p>
          <el-button :disabled="downloadState.busy.value" @click="download(detail.voucherFileId)">{{
            t("prepay.voucher")
          }}</el-button></template
        >
      </div>
    </el-dialog>
  </main>
</template>
<style scoped>
.prepay-results {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  height: 100%;
  min-height: 0;
  min-width: 0;
  overflow-wrap: anywhere;
}
.prepay-results > h1,
.prepay-results > p {
  margin: 0;
}
.prepay-mobile {
  display: none;
}
h2 {
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
}
@media (max-width: 767px) {
  .prepay-results {
    height: auto;
  }
  .prepay-results :deep(.data-table__body),
  .prepay-results :deep(.data-table__pager) {
    display: none;
  }
  .prepay-results :deep(.data-table__toolbar) {
    flex-wrap: wrap;
  }
  .prepay-results :deep(.data-table__tools) {
    min-width: 0;
    flex-wrap: wrap;
  }
  .prepay-mobile {
    display: block;
    padding: var(--tcrm-space-4);
    background: var(--tcrm-color-card);
    border: 1px solid var(--tcrm-color-border);
    border-radius: var(--tcrm-radius-card);
  }
  .prepay-record {
    padding: var(--tcrm-space-4) 0;
    border-bottom: 1px solid var(--tcrm-color-border);
  }
  dl {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--tcrm-space-2);
  }
  dd {
    margin: 0;
  }
}
</style>
