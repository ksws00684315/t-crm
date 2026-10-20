<script setup lang="ts">
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import ProductListPage from "@/views/product/product/index.vue";
import SheetSourcePage from "@/views/product/sheet-source/index.vue";
import ShopListPage from "@/views/product/shop/index.vue";

defineOptions({ name: "ShopManagementPage" });

/**
 * Products > Shop Management after the owner's menu decision (task 32 §2.1): one page, three
 * tabs — shops, source products and sheet sources. Each tab keeps the existing page
 * component, so queries and state stay where they were.
 */
const { t } = useI18n();
const activeTab = ref("shops");
</script>

<template>
  <div class="shop-management">
    <el-tabs v-model="activeTab" class="shop-management__tabs">
      <el-tab-pane :label="t('route.shops')" name="shops" lazy>
        <ShopListPage />
      </el-tab-pane>
      <el-tab-pane :label="t('route.product')" name="products" lazy>
        <ProductListPage />
      </el-tab-pane>
      <el-tab-pane :label="t('route.sheetSources')" name="sheet-sources" lazy>
        <SheetSourcePage />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.shop-management {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.shop-management__tabs {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.shop-management__tabs :deep(.el-tabs__content) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: visible;
}
.shop-management__tabs :deep(.el-tab-pane) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
</style>
