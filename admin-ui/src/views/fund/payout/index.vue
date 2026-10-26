<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import WithdrawalTasksPage from "@/views/fund/withdrawal/index.vue";
import { useUserStore } from "@/stores/user";
import { financeAbilities } from "@/utils/finance";
import PayoutAccountReviewPage from "@/views/fund/payout-account/index.vue";

defineOptions({ name: "FundPayoutPage" });

/**
 * Finance tasks keep commission withdrawals and the existing payout-account review in separate tabs.
 */
const { t } = useI18n();
const user = useUserStore();
const canViewWithdrawals = computed(() => financeAbilities(user.user).view);
const canReviewAccounts = computed(() => user.can("payout-account:review"));
const activeTab = ref(canViewWithdrawals.value ? "withdrawals" : "payout-accounts");
watch([canViewWithdrawals, canReviewAccounts], () => {
  if (activeTab.value === "withdrawals" && !canViewWithdrawals.value)
    activeTab.value = "payout-accounts";
  if (activeTab.value === "payout-accounts" && !canReviewAccounts.value)
    activeTab.value = "withdrawals";
});
</script>

<template>
  <div class="fund-payout">
    <el-tabs v-model="activeTab" class="fund-payout__tabs">
      <el-tab-pane v-if="canViewWithdrawals" :label="t('finance.tasks')" name="withdrawals" lazy
        ><WithdrawalTasksPage
      /></el-tab-pane>
      <el-tab-pane
        v-if="canReviewAccounts"
        :label="t('route.payoutAccounts')"
        name="payout-accounts"
        lazy
      >
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
