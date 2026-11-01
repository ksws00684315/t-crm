<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import { formatMoney } from "@/utils/orders";
import { isNegativeAmount, type WorkspaceFigures } from "@/utils/workspace";

defineOptions({ name: "HomeIncomeSection" });
const props = defineProps<{ figures: WorkspaceFigures }>();
const { t, locale } = useI18n();
const router = useRouter();
const money = (value: string) => formatMoney(value, locale.value);
const commissionNegative = computed(() => isNegativeAmount(props.figures.commissionIncome));
const availableNegative = computed(() => isNegativeAmount(props.figures.available));
function withdraw() {
  void router.push({ path: "/funds", query: { apply: "1" } });
}
</script>

<template>
  <section class="income" aria-labelledby="income-title">
    <h2 id="income-title" class="income__heading">{{ t("home.incomeTitle") }}</h2>
    <article class="income-card" data-test="income-card">
      <div class="income-band">
        <span class="tcrm-role-section">{{ t("home.incomeTotal") }}</span>
        <strong
          class="tcrm-amount-primary income-band__amount"
          :class="{ negative: isNegativeAmount(figures.totalIncome) }"
          data-test="income-total"
          >{{ money(figures.totalIncome) }}</strong
        >
        <span class="caption">{{ t("home.incomeTotalCaption") }}</span>
      </div>
      <ul class="income-list">
        <li class="income-list__row">
          <span class="dot dot--commission" aria-hidden="true" />
          <div class="income-list__text">
            <span class="tcrm-role-section">{{ t("home.incomeCommission") }}</span>
            <span class="caption">{{ t("home.incomeCommissionCaption") }}</span>
          </div>
          <strong
            class="tcrm-amount-detail income-list__amount"
            :class="{ negative: commissionNegative }"
            data-test="income-commission"
            >{{ money(figures.commissionIncome) }}</strong
          >
        </li>
        <li class="income-list__row">
          <span class="dot dot--prepay" aria-hidden="true" />
          <div class="income-list__text">
            <span class="tcrm-role-section">{{ t("home.incomePrepay") }}</span>
            <span class="caption">{{ t("home.incomePrepayCaption") }}</span>
          </div>
          <strong class="tcrm-amount-detail income-list__amount" data-test="income-prepay">{{
            money(figures.prepayPaid)
          }}</strong>
        </li>
      </ul>
    </article>
    <article class="withdraw-card" data-test="withdrawable-card">
      <div class="withdraw-card__main">
        <span class="tcrm-role-section withdraw-card__label">{{ t("home.withdrawable") }}</span>
        <strong
          class="tcrm-amount-primary withdraw-card__amount"
          :class="{ negative: availableNegative }"
          data-test="available-amount"
          >{{ money(figures.available) }}</strong
        >
        <span class="caption">{{ t("home.withdrawableCaption") }}</span>
      </div>
      <el-button type="primary" class="tcrm-button-large" data-test="withdraw" @click="withdraw">{{
        t("home.withdraw")
      }}</el-button>
    </article>
  </section>
</template>

<style scoped>
.income {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  gap: var(--tcrm-space-4);
  min-width: 0;
}
.income__heading {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
.income-card,
.withdraw-card {
  min-width: 0;
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}
.income-card {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
  align-items: center;
  gap: var(--tcrm-space-5);
  padding: var(--tcrm-space-4);
}
.income-band {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
  height: 100%;
  padding: var(--tcrm-space-5);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-order-band);
}
.income-band__amount {
  color: var(--tcrm-color-primary-text);
  overflow-wrap: anywhere;
}
.income-list {
  display: grid;
  min-width: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}
.income-list__row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--tcrm-space-3);
  padding: var(--tcrm-space-3) 0;
}
.income-list__row + .income-list__row {
  border-top: 1px solid var(--tcrm-color-border);
}
.income-list__text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-width: 0;
}
.income-list__amount {
  min-width: 0;
  text-align: right;
  overflow-wrap: anywhere;
}
.caption {
  color: var(--tcrm-color-text-secondary);
}
.dot {
  width: var(--tcrm-space-3);
  height: var(--tcrm-space-3);
  border-radius: 50%;
  background: var(--tcrm-color-primary);
}
.dot--prepay {
  background: var(--tcrm-color-success);
}
.withdraw-card {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  align-content: center;
  gap: var(--tcrm-space-4) var(--tcrm-space-6);
  padding: var(--tcrm-space-5);
  border-color: var(--tcrm-color-success);
  background: var(--tcrm-color-success-bg);
}
.withdraw-card__main {
  display: flex;
  flex: 0 1 auto;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--tcrm-space-2);
  min-width: 0;
  color: var(--tcrm-color-success);
}
.withdraw-card__main .caption {
  color: var(--tcrm-color-text-secondary);
}
.withdraw-card__amount {
  overflow-wrap: anywhere;
}
.withdraw-card .el-button {
  flex: none;
}
.negative {
  color: var(--tcrm-color-danger);
}
@media (max-width: 1279px) {
  .income {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 767px) {
  .income-card {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--tcrm-space-4);
    padding: var(--tcrm-space-3);
  }
  .income-band,
  .withdraw-card {
    padding: var(--tcrm-space-4);
  }
}
</style>
