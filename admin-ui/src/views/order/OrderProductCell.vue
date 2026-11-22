<script setup lang="ts">
import CopyText from "@/components/CopyText.vue";
import OrderProductImage from "./OrderProductImage.vue";
defineProps<{
  imageUrl?: string | null;
  name?: string | null;
  productId?: string | null;
  sku?: string | null;
  separateSku?: boolean;
}>();
</script>
<template>
  <div class="order-product-cell">
    <OrderProductImage :image-url="imageUrl" />
    <div class="order-product-cell__text">
      <span>{{ name || "-" }}</span>
      <CopyText v-if="productId" :value="productId" />
      <span v-if="!separateSku" class="order-product-cell__sku">{{ sku || "-" }}</span>
      <slot />
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
