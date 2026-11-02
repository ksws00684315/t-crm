<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import AmountText from "@/components/AmountText.vue";
import { formatMoney } from "@/utils/orders";
import { isNegativeAmount, type WorkspaceFigures } from "@/utils/workspace";

defineOptions({ name: "HomeCommissionSection" });
const props = defineProps<{
  figures: WorkspaceFigures;
  salesCny: string;
  /** Commission rate text such as "12%"; null while the account has no rate configured. */
  rateText: string | null;
}>();
const { t, locale } = useI18n();
const router = useRouter();
const money = (value: string) => formatMoney(value, locale.value);

type CardKey = "settled" | "frozen" | "occupied" | "commissionPaid";
const cards = computed(() => {
  const items: {
    key: CardKey;
    tone: string;
    label: string;
    caption: string;
    action: string;
    to: string;
  }[] = [
    {
      key: "settled",
      tone: "success",
      label: "home.settled",
      caption: "home.settledCaption",
      action: "home.myOrders",
      to: "/orders"
    },
    {
      key: "frozen",
      tone: "warning",
      label: "home.frozen",
      caption: "home.frozenCaption",
      action: "home.myOrders",
      to: "/orders"
    },
    {
      key: "occupied",
      tone: "neutral",
      label: "home.withdrawing",
      caption: "home.withdrawingCaption",
      action: "home.myRequests",
      to: "/funds"
    },
    {
      key: "commissionPaid",
      tone: "neutral",
      label: "home.withdrawn",
      caption: "home.withdrawnCaption",
      action: "home.myRequests",
      to: "/funds"
    }
  ];
  return items.map(item => ({ ...item, amount: props.figures[item.key] }));
});
function go(path: string) {
  void router.push(path);
}
</script>

<template>
  <section class="details" aria-labelledby="details-title">
    <header class="details__heading">
      <h2 id="details-title">{{ t("home.detailsTitle") }}</h2>
      <span class="caption">{{ t("home.cumulative") }}</span>
    </header>
    <p class="caption details__subtitle">{{ t("home.detailsSubtitle") }}</p>
    <div class="flow">
      <div class="flow__item">
        <span class="flow__label">{{ t("home.sales") }}</span>
        <strong class="tcrm-amount-detail flow__sales" data-test="sales-amount"
          ><AmountText :value="money(salesCny)"
        /></strong>
        <span class="caption">{{ t("home.salesCaption") }}</span>
      </div>
      <span class="flow__rate" data-test="commission-rate"
        >× {{ t("home.currentRate") }} {{ rateText ?? t("account.ratePending") }}</span
      >
      <div class="flow__item">
        <span class="flow__label">{{ t("home.estimated") }}</span>
        <strong class="tcrm-amount-detail" data-test="estimated-amount"
          ><AmountText :value="money(figures.estimatedCommission)"
        /></strong>
        <span class="caption">{{ t("home.estimatedCaption") }}</span>
      </div>
    </div>
    <div class="cards">
      <article
        v-for="card in cards"
        :key="card.key"
        class="card"
        :class="`card--${card.tone}`"
        :data-test="`card-${card.key}`"
      >
        <span class="card__label">{{ t(card.label) }}</span>
        <strong
          class="tcrm-amount-detail card__amount"
          :class="{ negative: isNegativeAmount(card.amount) }"
          :data-test="`card-${card.key}-amount`"
          ><AmountText :value="money(card.amount)"
        /></strong>
        <span class="caption">{{ t(card.caption) }}</span>
        <div class="card__action">
          <el-button :data-test="`card-${card.key}-action`" @click="go(card.to)">{{
            t(card.action)
          }}</el-button>
        </div>
      </article>
    </div>
    <p class="caption details__footnote">{{ t("home.detailsFootnote") }}</p>
  </section>
</template>

<style scoped>
.details {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
  padding: var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}
.details__heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
}
.details__heading h2,
.details__subtitle,
.details__footnote {
  margin: 0;
}
.caption {
  color: var(--tcrm-color-text-secondary);
}
.flow {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: flex-start;
  gap: var(--tcrm-space-3) var(--tcrm-space-5);
}
.flow__item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--tcrm-space-1);
  min-width: 0;
  max-width: 100%;
}
.flow__item strong {
  overflow-wrap: break-word;
}
.flow__sales {
  color: var(--tcrm-color-primary-text);
}
.flow__rate {
  align-self: center;
  color: var(--tcrm-color-text-secondary);
}
.cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--tcrm-space-4);
}
.card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--tcrm-space-1);
  min-width: 0;
  padding: var(--tcrm-space-4);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-muted);
}
.card--success {
  background: var(--tcrm-color-success-bg);
  color: var(--tcrm-color-success);
}
.card--warning {
  background: var(--tcrm-color-warning-bg);
  color: var(--tcrm-color-warning);
}
.card__label {
  font-weight: 600;
}
.card__amount {
  overflow-wrap: break-word;
}
.card .caption {
  color: var(--tcrm-color-text-secondary);
}
.card__action {
  margin-top: auto;
  padding-top: var(--tcrm-space-3);
}
.negative {
  color: var(--tcrm-color-danger);
}
@media (max-width: 1023px) {
  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 767px) {
  .details {
    padding: var(--tcrm-space-3);
  }
  .cards {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--tcrm-space-3);
  }
}
</style>
