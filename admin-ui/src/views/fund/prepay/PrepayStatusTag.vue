<script setup lang="ts">
import { useI18n } from "vue-i18n";
import type { PrepayStatus } from "@/api/commission/prepay";
defineProps<{ status: PrepayStatus; label?: string }>();
const { t } = useI18n();
</script>
<template>
  <el-tag effect="light" :class="['prepay-status-tag', `prepay-status-${status}`]">
    {{ label ?? t(`prepay.status.${status}`) }}
  </el-tag>
</template>
<style scoped>
.prepay-status-tag {
  border-color: transparent;
}
/* Waiting states, including approved-but-unpaid, share the semantic warning colour. */
.prepay-status-WAITING_DETAILS,
.prepay-status-PENDING_OPERATIONS_REVIEW,
.prepay-status-PENDING_FINANCE_REVIEW,
.prepay-status-APPROVED {
  color: var(--tcrm-color-warning);
  background: var(--tcrm-color-warning-bg);
}
.prepay-status-PAID {
  color: var(--tcrm-color-success);
  background: var(--tcrm-color-success-bg);
}
.prepay-status-REJECTED {
  color: var(--tcrm-color-danger);
  background: var(--tcrm-color-danger-bg);
}
.prepay-status-CANCELLED {
  color: var(--tcrm-color-text-secondary);
  background: var(--tcrm-color-muted);
}
</style>
