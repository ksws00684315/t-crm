<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import WithdrawalTasksPage from "@/views/fund/withdrawal/index.vue";
import { useUserStore } from "@/stores/user";
import { financeAbilities } from "@/utils/finance";

defineOptions({ name: "FundPayoutPage" });

/**
 * Finance tasks show commission withdrawals only (task 80). The payout-account review tab was
 * removed on 2026-10-29 (task 82): creator payout accounts take effect at once and are read from
 * the creator list's fund dialog.
 */
const { t } = useI18n();
const user = useUserStore();
const canViewWithdrawals = computed(() => financeAbilities(user.user).view);
const activeTab = ref("withdrawals");
</script>

<template>
  <div class="fund-payout">
    <el-tabs v-model="activeTab" class="fund-payout__tabs">
      <el-tab-pane v-if="canViewWithdrawals" :label="t('finance.tasks')" name="withdrawals" lazy
        ><WithdrawalTasksPage
      /></el-tab-pane>
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
