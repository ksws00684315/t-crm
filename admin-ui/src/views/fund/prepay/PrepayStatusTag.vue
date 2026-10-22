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
.prepay-status-WAITING_DETAILS {
  color: #946200;
  background: #fff4ce;
}
.prepay-status-PENDING_OPERATIONS_REVIEW {
  color: #2456c4;
  background: #eaf0ff;
}
.prepay-status-PENDING_FINANCE_REVIEW {
  color: #7540b2;
  background: #f3ebff;
}
.prepay-status-APPROVED {
  color: #007782;
  background: #def7f7;
}
.prepay-status-PAID {
  color: #237a44;
  background: #e6f6ec;
}
.prepay-status-REJECTED {
  color: #b52c33;
  background: #ffebed;
}
.prepay-status-CANCELLED {
  color: #59636f;
  background: #edf0f3;
}
</style>
