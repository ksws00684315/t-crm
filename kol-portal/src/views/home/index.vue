<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { getMe } from "@/api/auth";
import {
  getFundSummary,
  getSalesTotal,
  type CreatorSalesTotal,
  type FundSummary
} from "@/api/creator";
import { useSessionStore } from "@/stores/session";
import { formatInstant } from "@/utils/format";
import { rateLabel, workspaceFigures } from "@/utils/workspace";
import CommissionSection from "./CommissionSection.vue";
import IncomeSection from "./IncomeSection.vue";
import TrendSection from "./TrendSection.vue";

defineOptions({ name: "HomePage" });
const { t } = useI18n();
const session = useSessionStore();
const summary = ref<FundSummary | null>(null);
const salesTotal = ref<CreatorSalesTotal | null>(null);
const loading = ref(true);
const failed = ref(false);
const trend = ref<InstanceType<typeof TrendSection> | null>(null);
const figures = computed(() => (summary.value ? workspaceFigures(summary.value) : null));
const rateText = computed(() => rateLabel(session.kol?.commissionRate));

async function load() {
  loading.value = true;
  failed.value = false;
  // The rate belongs to the account profile; a failed refresh keeps the stored one.
  const profile = getMe()
    .then(me => session.updateProfile(me))
    .catch(() => undefined);
  try {
    const [fund, sales] = await Promise.all([getFundSummary(), getSalesTotal()]);
    summary.value = fund.fundSummary;
    salesTotal.value = sales;
  } catch {
    // Never show zeros for figures that did not load.
    summary.value = null;
    salesTotal.value = null;
    failed.value = true;
  } finally {
    await profile;
    loading.value = false;
  }
}
function refresh() {
  void load();
  void trend.value?.reload();
}
onMounted(load);
</script>

<template>
  <section class="dashboard" aria-labelledby="dashboard-title">
    <header class="page-heading">
      <div>
        <h1 id="dashboard-title">{{ t("home.title") }}</h1>
        <p v-if="summary" data-test="as-of">
          {{ t("home.asOf", { time: formatInstant(summary.asOf) }) }}
        </p>
      </div>
      <el-button :loading="loading" data-test="refresh" @click="refresh">{{
        t("home.refresh")
      }}</el-button>
    </header>
    <div v-if="failed" class="load-failed" role="alert" data-test="load-failed">
      <el-alert type="error" :closable="false" :title="t('home.loadFailed')" show-icon />
      <el-button data-test="retry" @click="load">{{ t("home.retry") }}</el-button>
    </div>
    <el-skeleton v-if="loading && !summary" :rows="5" animated />
    <template v-else-if="figures && salesTotal">
      <IncomeSection :figures="figures" />
      <CommissionSection
        :figures="figures"
        :sales-cny="salesTotal.salesCny"
        :rate-text="rateText"
      />
    </template>
    <TrendSection ref="trend" />
  </section>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
}
h1 {
  margin: 0;
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  font-weight: 600;
}
.page-heading p {
  margin: var(--tcrm-space-1) 0 0;
  color: var(--tcrm-color-text-secondary);
}
.load-failed {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.load-failed .el-alert {
  flex: 1 1 auto;
  width: auto;
}
@media (max-width: 767px) {
  .page-heading {
    align-items: flex-start;
  }
}
</style>
