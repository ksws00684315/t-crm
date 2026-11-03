<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import AmountText from "@/components/AmountText.vue";
import { isNegativeAmount, type WorkspaceFigures } from "@/utils/workspace";

defineOptions({ name: "HomeIncomeSection" });
const props = defineProps<{ figures: WorkspaceFigures }>();
const { t } = useI18n();
const router = useRouter();
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
          ><AmountText :value="figures.totalIncome"
        /></strong>
        <span class="caption">{{ t("home.incomeTotalCaption") }}</span>
      </div>
      <ul class="income-list">
        <li class="income-list__row">
          <div class="income-list__head">
            <span class="dot dot--commission" aria-hidden="true" />
            <div class="income-list__text">
              <span class="tcrm-role-section">{{ t("home.incomeCommission") }}</span>
              <span class="caption">{{ t("home.incomeCommissionCaption") }}</span>
            </div>
          </div>
          <strong
            class="tcrm-amount-detail income-list__amount"
            :class="{ negative: commissionNegative }"
            data-test="income-commission"
            ><AmountText :value="figures.commissionIncome"
          /></strong>
        </li>
        <li class="income-list__row">
          <div class="income-list__head">
            <span class="dot dot--prepay" aria-hidden="true" />
            <div class="income-list__text">
              <span class="tcrm-role-section">{{ t("home.incomePrepay") }}</span>
              <span class="caption">{{ t("home.incomePrepayCaption") }}</span>
            </div>
          </div>
          <strong class="tcrm-amount-detail income-list__amount" data-test="income-prepay"
            ><AmountText :value="figures.prepayPaid"
          /></strong>
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
          ><AmountText :value="figures.available"
        /></strong>
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
  /* 2:3, except that the band never gets narrower than its amount (no break inside the number). */
  grid-template-columns: minmax(min-content, 2fr) minmax(0, 3fr);
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
  overflow-wrap: break-word;
}
@media (min-width: 768px) {
  .income-band__amount {
    white-space: nowrap;
  }
  /* Chrome still breaks at <wbr> under nowrap, so the break hints are dropped here. */
  .income-band__amount :deep(wbr) {
    display: none;
  }
}
.income-list {
  display: grid;
  min-width: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}
.income-list__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-2) var(--tcrm-space-3);
  padding: var(--tcrm-space-3) 0;
}
.income-list__row + .income-list__row {
  border-top: 1px solid var(--tcrm-color-border);
}
.income-list__head {
  display: flex;
  flex: 1 1 14em;
  align-items: center;
  gap: var(--tcrm-space-3);
  min-width: 0;
}
.income-list__text {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  align-items: flex-start;
  min-width: 0;
}
/* A long amount drops below the text instead of squeezing it, and stays at the list's right end. */
.income-list__amount {
  flex: 0 1 auto;
  min-width: 0;
  margin-left: auto;
  text-align: right;
  overflow-wrap: break-word;
}
.caption {
  color: var(--tcrm-color-text-secondary);
}
.dot {
  flex: none;
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
  overflow-wrap: break-word;
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
  /* On a phone the button always sits under the amount. */
  .withdraw-card {
    flex-direction: column;
    flex-wrap: nowrap;
    align-items: flex-start;
    align-content: flex-start;
  }
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
