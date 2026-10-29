<script setup lang="ts">
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import OrderListPage from "@/views/order/index.vue";

defineOptions({ name: "OrderQueryPage" });

/**
 * Order Query keeps only the order list after the owner's 2026-10-29 scope update.
 */
const { t } = useI18n();
const activeTab = ref("orders");
</script>

<template>
  <div class="order-query">
    <el-tabs v-model="activeTab" class="order-query__tabs">
      <el-tab-pane :label="t('route.orderList')" name="orders" lazy>
        <OrderListPage />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.order-query {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.order-query__tabs {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.order-query__tabs :deep(.el-tabs__content) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: visible;
}
.order-query__tabs :deep(.el-tab-pane) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
</style>
