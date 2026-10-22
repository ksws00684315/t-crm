<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
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

defineOptions({ name: "PrepayResultsPage" });
const { t, locale } = useI18n();
const tab = ref("payments");
const rows = ref<PrepayResult[]>([]);
const ledger = ref<PrepayEntry[]>([]);
const page = ref(1);
const total = ref(0);
const size = 20;
const detail = ref<PrepayResult | null>(null);
const detailVisible = ref(false);
const { busy, errorText, submit } = useSubmission();
const downloadState = useSubmission();
let generation = 0;
function instant(value: string) {
  const time = new Date(value);
  return Number.isNaN(time.getTime()) ? "—" : time.toLocaleString(locale.value);
}
async function load() {
  if (busy.value) return;
  const request = ++generation;
  await submit(async () => {
    rows.value = [];
    ledger.value = [];
    total.value = 0;
    if (tab.value === "payments") {
      const result = await listPrepays(page.value, size);
      if (request === generation) {
        rows.value = result.items;
        total.value = Number(result.total);
      }
    } else {
      const result = await prepayLedger(page.value, size);
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
  page.value = value;
  void load();
}
watch(tab, () => {
  page.value = 1;
  void load();
});
onMounted(load);
</script>
<template>
  <main class="prepay-results" data-test="prepay-results">
    <h1>{{ t("prepay.title") }}</h1>
    <p>{{ t("prepay.paidOnly") }}</p>
    <el-radio-group
      v-model="tab"
      :disabled="busy"
      :aria-label="t('prepay.title')"
      data-test="prepay-tabs"
      ><el-radio-button value="payments">{{ t("prepay.payments") }}</el-radio-button
      ><el-radio-button value="ledger">{{ t("prepay.ledger") }}</el-radio-button></el-radio-group
    >
    <el-button :loading="busy" data-test="prepay-refresh" @click="load">{{
      t("prepay.refresh")
    }}</el-button>
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
    <section v-loading="busy" class="prepay-card">
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
          <h2>{{ row.prepayNo }}</h2>
          <dl>
            <dt>{{ t("prepay.amount") }}</dt>
            <dd>{{ row.payAmountCny }} CNY</dd>
            <dt>{{ t("prepay.payTime") }}</dt>
            <dd>{{ instant(row.payTime) }}</dd>
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
          <el-button
            data-test="prepay-result-detail"
            :disabled="downloadState.busy.value"
            @click="open(row)"
            >{{ t("prepay.detail") }}</el-button
          >
          <el-button
            data-test="prepay-voucher-download"
            :disabled="downloadState.busy.value"
            @click="download(row.voucherFileId)"
            >{{ t("prepay.voucher") }}</el-button
          >
        </article>
      </template>
      <template v-else
        ><article
          v-for="entry in ledger"
          :key="entry.entryId"
          class="prepay-record"
          data-test="prepay-ledger-row"
        >
          <h2>{{ entry.prepayNo }}</h2>
          <p>{{ t(`prepay.entry.${entry.entryType}`) }} · {{ entry.amountCny }} CNY</p>
          <p>{{ instant(entry.occurredAt) }}</p>
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
          ><h2>{{ detail.prepayNo }}</h2>
          <p>
            {{ detail.payAmountCny }} CNY · {{ detail.payMethod }} · {{ instant(detail.payTime) }}
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
  min-width: 0;
  overflow-wrap: anywhere;
}
.prepay-card {
  background: var(--tcrm-color-card);
  border-radius: var(--tcrm-radius-card);
  padding: var(--tcrm-space-4);
  margin-top: var(--tcrm-space-4);
}
.prepay-record {
  padding: var(--tcrm-space-4) 0;
  border-bottom: 1px solid var(--tcrm-color-border);
}
h2 {
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
}
dl {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
  gap: var(--tcrm-space-2);
}
dd {
  margin: 0;
}
@media (max-width: 767px) {
  dl {
    grid-template-columns: minmax(0, 1fr);
  }
  .prepay-record {
    padding: var(--tcrm-space-4);
  }
}
</style>
