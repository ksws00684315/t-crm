<script setup lang="ts">
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import PayoutAccountReviewPage from "@/views/fund/payout-account/index.vue";

defineOptions({ name: "FundPayoutPage" });

/**
 * Finance > Creator Payouts (task 32 §2.1): the payout-account review is the first tab; the
 * withdrawal feature itself will be added here later.
 */
const { t } = useI18n();
const activeTab = ref("payout-accounts");
</script>

<template>
  <div class="fund-payout">
    <el-tabs v-model="activeTab" class="fund-payout__tabs">
      <el-tab-pane :label="t('route.payoutAccounts')" name="payout-accounts" lazy>
        <PayoutAccountReviewPage />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.fund-payout {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.fund-payout__tabs {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.fund-payout__tabs :deep(.el-tabs__content) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: visible;
}
.fund-payout__tabs :deep(.el-tab-pane) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
</style>
