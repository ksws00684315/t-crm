<script setup lang="ts">
import { useI18n } from "vue-i18n";
import CopyText from "@/components/CopyText.vue";
defineProps<{
  imageUrl?: string | null;
  name?: string | null;
  productId?: string | null;
  sku?: string | null;
}>();
const { t } = useI18n();
</script>
<template>
  <div class="order-product-cell">
    <el-image
      v-if="imageUrl"
      class="order-product-cell__image"
      :src="imageUrl"
      :preview-src-list="[imageUrl]"
      fit="cover"
      preview-teleported
      alt=""
      data-test="detail-product-image"
    />
    <span
      v-else
      class="order-product-cell__image order-product-cell__missing"
      :aria-label="t('order.itemImageMissing')"
      >-</span
    >
    <div class="order-product-cell__text">
      <span>{{ name || "-" }}</span>
      <CopyText v-if="productId" :value="productId" />
      <span class="order-product-cell__sku">{{ sku || "-" }}</span>
    </div>
  </div>
</template>
<style scoped>
.order-product-cell {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-3);
  min-width: 0;
}
.order-product-cell__image {
  width: 40px;
  height: 48px;
  flex-shrink: 0;
  border-radius: var(--tcrm-radius-tag);
}
.order-product-cell__missing {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--tcrm-color-text-secondary);
  background: var(--tcrm-color-muted);
}
.order-product-cell__text {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-1);
  min-width: 0;
  overflow-wrap: anywhere;
}
.order-product-cell__sku {
  color: var(--tcrm-color-text-secondary);
}
</style>
