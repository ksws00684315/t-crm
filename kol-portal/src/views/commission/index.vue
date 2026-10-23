<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import FinancePreviewNotice from "@/components/FinancePreviewNotice.vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  cancelWithdrawal,
  downloadWithdrawalVoucher,
  getMyIncome,
  getMyWithdrawal,
  getWithdrawalConditions,
  listMyWithdrawals
} from "@/api/commission/finance";
import type { IncomeDetail, Withdrawal } from "@/api/commission/finance-types";
import FinanceMetrics from "./FinanceMetrics.vue";
import { previewContext } from "@/utils/finance";
import { formatInstant, formatPrice, formatRate } from "@/utils/format";
import { useSubmission } from "@/utils/useSubmission";
import ApplyDialog from "./ApplyDialog.vue";
defineOptions({ name: "CreatorCommissionPage" });
const { t } = useI18n();
const detail = ref<IncomeDetail>();
const rows = ref<Withdrawal[]>([]);
const total = ref(0);
const page = ref(1);
const loading = ref(false);
const failed = ref(false);
const minimum = ref("200.00");
const tab = ref("ledger");
const applyOpen = ref(false);
const previous = ref<Withdrawal | null>(null);
const selected = ref<Withdrawal>();
const recordOpen = ref(false);
const submission = useSubmission();
async function load() {
  loading.value = true;
  failed.value = false;
  try {
    const [income, conditions, result] = await Promise.all([
      getMyIncome({}),
      getWithdrawalConditions(),
      listMyWithdrawals(page.value, 20)
    ]);
    detail.value = income;
    minimum.value = conditions.minimum;
    rows.value = result.items;
    total.value = Number(result.total);
  } catch {
    failed.value = true;
    detail.value = undefined;
    rows.value = [];
  } finally {
    loading.value = false;
  }
}
function apply(row: Withdrawal | null = null) {
  previous.value = row;
  applyOpen.value = true;
}
function canWithdraw(row: Withdrawal) {
  return row.status === "PENDING_REVIEW";
}
function canResubmit(row: Withdrawal) {
  return row.status === "REJECTED";
}
async function cancel(row: Withdrawal) {
  try {
    await ElMessageBox.confirm(t("finance.withdrawConfirm"), t("finance.withdraw"), {
      confirmButtonText: t("finance.confirm"),
      cancelButtonText: t("finance.cancel")
    });
  } catch {
    return;
  }
  await submission.submit(async () => {
    await cancelWithdrawal(row.id);
    ElMessage.success(t("finance.saved"));
    await load();
  });
}
async function view(row: Withdrawal) {
  await submission.submit(async () => {
    selected.value = await getMyWithdrawal(row.id);
    recordOpen.value = true;
  });
}
async function voucher(id: string) {
  if (!id) return;
  await submission.submit(async () => {
    const blob = await downloadWithdrawalVoucher(id);
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "payment-voucher";
    link.click();
    URL.revokeObjectURL(url);
  });
}
const applicationFields = computed(
  () => ["applicationNo", "applicationType", "amount", "currency", "createdAt", "status"] as const
);
watch(page, load);
watch(
  () => previewContext.scenario,
  () => {
    page.value = 1;
    recordOpen.value = false;
    applyOpen.value = false;
    void load();
  }
);
onMounted(load);
</script>
<template>
  <div class="creator-commission" data-test="creator-commission-page">
    <FinancePreviewNotice />
    <div class="finance-content">
      <div class="page-heading">
        <h1>{{ t("finance.overview") }}</h1>
        <div>
          <el-button data-test="creator-refresh" :loading="loading" @click="load">{{
            t("finance.refresh")
          }}</el-button
          ><el-button
            type="primary"
            data-test="creator-apply"
            :disabled="!detail"
            @click="apply()"
            >{{ t("finance.apply") }}</el-button
          >
        </div>
      </div>
      <el-alert v-if="failed" :title="t('finance.loadFailed')" type="error" :closable="false"
        ><el-button data-test="creator-retry" @click="load">{{
          t("finance.retry")
        }}</el-button></el-alert
      >
      <el-alert
        v-if="submission.errorText.value"
        :title="submission.errorText.value"
        type="error"
        :closable="false"
      />
      <div v-loading="loading" class="content">
        <template v-if="detail"
          ><FinanceMetrics :metrics="detail.income" reconciliation />
          <p>{{ t("finance.currentNote") }}</p>
          <p>{{ t("finance.balanceNote") }}</p>
          <el-alert
            :title="t('finance.thresholdNote', { minimum, available: detail.income.available })"
            :closable="false"
          /><el-alert
            v-if="detail.income.unverified"
            :title="t('finance.unverifiedNotice', { count: detail.income.unverified })"
            type="warning"
            :closable="false"
          />
          <el-tabs v-model="tab">
            <el-tab-pane :label="t('finance.commissionLines')" name="ledger">
              <el-empty v-if="!detail.lines.length" />
              <div v-else class="records">
                <article
                  v-for="line in detail.lines"
                  :key="line.id"
                  class="record"
                  data-test="creator-ledger-record"
                >
                  <div class="record-title">
                    <strong>{{ line.product }}</strong
                    ><el-tag :type="line.releaseBasis === 'CONFIRMED' ? 'success' : 'warning'">{{
                      t(`finance.${line.releaseBasis}`)
                    }}</el-tag>
                  </div>
                  <dl>
                    <div
                      v-for="key in [
                        'orderNo',
                        'itemId',
                        'base',
                        'rate',
                        'rateVersion',
                        'ruleVersion',
                        'commission',
                        'adjustment',
                        'businessAt'
                      ] as const"
                      :key="key"
                    >
                      <dt>{{ t(`finance.${key}`) }}</dt>
                      <dd>
                        {{
                          line[key] === null
                            ? t("finance.unverified")
                            : key === "businessAt"
                              ? formatInstant(line.businessAt)
                              : key === "rate"
                                ? formatRate(line.rate)
                                : line[key]
                        }}
                      </dd>
                    </div>
                  </dl>
                </article>
              </div>
            </el-tab-pane>
            <el-tab-pane :label="t('finance.withdrawals')" name="withdrawals"
              ><el-empty v-if="!rows.length" />
              <div v-else class="records">
                <article
                  v-for="row in rows"
                  :key="row.id"
                  class="record"
                  data-test="creator-withdrawal-record"
                >
                  <div class="record-title">
                    <strong>{{ row.applicationNo }}</strong
                    ><el-tag>{{ t(`finance.${row.status}`) }}</el-tag>
                  </div>
                  <p>{{ formatPrice(row.amount, "CNY") }} · {{ formatInstant(row.createdAt) }}</p>
                  <el-alert
                    v-if="row.paused"
                    :title="t('finance.paused')"
                    :description="t('finance.refundPause')"
                    type="error"
                    :closable="false"
                  />
                  <p v-if="row.reviewNote">
                    {{
                      row.reviewNote === "rejectExample"
                        ? t("finance.rejectExample")
                        : row.reviewNote
                    }}
                  </p>
                  <div class="record-actions">
                    <el-button data-test="creator-record-view" @click="view(row)">{{
                      t("finance.view")
                    }}</el-button
                    ><el-button
                      v-if="canWithdraw(row)"
                      data-test="creator-withdraw"
                      :loading="submission.busy.value"
                      @click="cancel(row)"
                      >{{ t("finance.withdraw") }}</el-button
                    ><el-button
                      v-if="canResubmit(row)"
                      data-test="creator-resubmit"
                      type="primary"
                      @click="apply(row)"
                      >{{ t("finance.resubmit") }}</el-button
                    >
                  </div>
                </article>
              </div>
              <el-pagination
                v-model:current-page="page"
                :page-size="20"
                :total="total"
                layout="prev, pager, next"
            /></el-tab-pane>
            <el-tab-pane :label="t('finance.payments')" name="payments"
              ><p>{{ t("finance.prepayReadOnly") }}</p>
              <el-empty v-if="!detail.payments.length" />
              <div v-else class="records">
                <article
                  v-for="payment in detail.payments"
                  :key="payment.id"
                  class="record"
                  data-test="creator-payment-record"
                >
                  <div class="record-title">
                    <strong>{{ payment.sourceNo }}</strong
                    ><el-tag>{{ t(`finance.${payment.type}`) }}</el-tag>
                  </div>
                  <dl>
                    <div>
                      <dt>{{ t("finance.amountCny") }}</dt>
                      <dd>{{ formatPrice(payment.amountCny, "CNY") }}</dd>
                    </div>
                    <div>
                      <dt>{{ t("finance.paidAt") }}</dt>
                      <dd>{{ formatInstant(payment.paidAt) }}</dd>
                    </div>
                    <template v-if="payment.type === 'PREPAY'"
                      ><div>
                        <dt>{{ t("finance.deduct") }}</dt>
                        <dd>{{ t(payment.deduct ? "finance.yes" : "finance.no") }}</dd>
                      </div>
                      <template v-if="payment.deduct"
                        ><div>
                          <dt>{{ t("finance.deducted") }}</dt>
                          <dd>{{ formatPrice(payment.deducted, "CNY") }}</dd>
                        </div>
                        <div>
                          <dt>{{ t("finance.pendingDeduction") }}</dt>
                          <dd>{{ formatPrice(payment.pending, "CNY") }}</dd>
                        </div></template
                      ></template
                    ><template v-else
                      ><div>
                        <dt>{{ t("finance.actualAmount") }}</dt>
                        <dd>{{ payment.actualAmount }} {{ payment.actualCurrency }}</dd>
                      </div></template
                    >
                  </dl>
                  <el-button
                    v-if="payment.type === 'COMMISSION' && payment.voucher"
                    data-test="creator-payment-voucher"
                    @click="voucher(payment.voucher)"
                    >{{ t("finance.voucherPreview") }}</el-button
                  >
                </article>
              </div></el-tab-pane
            >
          </el-tabs></template
        >
      </div>
      <ApplyDialog
        v-model="applyOpen"
        :minimum="minimum"
        :available="detail?.income.available ?? '0.00'"
        :previous="previous"
        @saved="load"
      />
      <el-dialog v-model="recordOpen" :title="t('finance.view')" width="min(720px, 94vw)"
        ><div v-if="selected" class="application-detail" data-test="creator-record-detail">
          <dl>
            <div v-for="key in applicationFields" :key="key">
              <dt>{{ t(`finance.${key}`) }}</dt>
              <dd>
                {{
                  key === "applicationType"
                    ? t("finance.COMMISSION")
                    : key === "status"
                      ? t(`finance.${selected.status}`)
                      : key === "createdAt"
                        ? formatInstant(selected.createdAt)
                        : selected[key as keyof Withdrawal]
                }}
              </dd>
            </div>
            <div>
              <dt>{{ t("finance.recipient") }}</dt>
              <dd>
                {{ selected.account.type }} · {{ selected.account.account }} ·
                {{ selected.account.holder }} {{ selected.account.currency }}
                {{ selected.account.network }}
              </dd>
            </div>
            <div>
              <dt>{{ t("finance.note") }}</dt>
              <dd>{{ selected.note || "—" }}</dd>
            </div>
            <div>
              <dt>{{ t("finance.reviewNote") }}</dt>
              <dd>
                {{
                  selected.reviewNote === "rejectExample"
                    ? t("finance.rejectExample")
                    : selected.reviewNote || "—"
                }}
              </dd>
            </div>
          </dl>
          <h2>{{ t("finance.approvals") }}</h2>
          <el-timeline
            ><el-timeline-item
              v-for="(event, index) in selected.history"
              :key="index"
              :timestamp="formatInstant(event.at)"
              >{{ t(`finance.${event.action}`) }} · {{ event.actor }}
              <p>{{ event.note }}</p></el-timeline-item
            ></el-timeline
          ><template v-if="selected.payment"
            ><h2>{{ t("finance.paymentResult") }}</h2>
            <dl>
              <div>
                <dt>{{ t("finance.actualAmount") }}</dt>
                <dd>{{ selected.payment.actualAmount }} {{ selected.payment.actualCurrency }}</dd>
              </div>
              <div>
                <dt>{{ t("finance.writeOff") }}</dt>
                <dd>{{ selected.payment.amountCny }} CNY</dd>
              </div>
              <div>
                <dt>{{ t("finance.paidAt") }}</dt>
                <dd>{{ formatInstant(selected.payment.paidAt) }}</dd>
              </div>
              <div>
                <dt>{{ t("finance.method") }}</dt>
                <dd>{{ selected.payment.method }}</dd>
              </div>
            </dl>
            <el-button
              :disabled="!selected.payment.voucher"
              @click="voucher(selected.payment.voucher)"
              >{{ t("finance.voucherPreview") }}</el-button
            ></template
          >
        </div></el-dialog
      >
    </div>
  </div>
</template>
<style scoped>
.finance-content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  min-width: 0;
  gap: var(--tcrm-space-4);
}
.creator-commission,
.content {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
.page-heading,
.record-title,
.record-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-2);
  flex-wrap: wrap;
}
h1 {
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  margin: 0;
}
h2 {
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
}
.records {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--tcrm-space-4);
  margin-top: var(--tcrm-space-4);
}
.record {
  padding: var(--tcrm-space-4);
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-bg-color);
  min-width: 0;
  overflow-wrap: anywhere;
}
.record-actions {
  justify-content: flex-start;
}
p {
  margin: 0;
  color: var(--el-text-color-secondary);
}
dl {
  margin: var(--tcrm-space-3) 0;
  display: grid;
  gap: var(--tcrm-space-2);
}
dl > div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.5fr);
  gap: var(--tcrm-space-3);
}
dt {
  color: var(--el-text-color-secondary);
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.application-detail {
  min-width: 0;
  overflow-wrap: anywhere;
}
@media (max-width: 767px) {
  .records {
    grid-template-columns: minmax(0, 1fr);
  }
  .page-heading > div {
    display: flex;
    flex-wrap: wrap;
    gap: var(--tcrm-space-2);
  }
  :deep(.el-tabs__item) {
    padding: 0 var(--tcrm-space-3);
  }
  .record-actions > .el-button {
    margin-left: 0;
  }
}
</style>
