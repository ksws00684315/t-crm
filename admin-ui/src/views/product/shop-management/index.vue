<script setup lang="ts">
import ShopListPage from "@/views/product/shop/index.vue";

defineOptions({ name: "ShopManagementPage" });
</script>

<template>
  <div class="shop-management">
    <ShopListPage />
  </div>
</template>

<style scoped>
.shop-management {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
</style>
