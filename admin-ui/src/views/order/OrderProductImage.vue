<script setup lang="ts">
import { useI18n } from "vue-i18n";

defineProps<{ imageUrl?: string | null }>();
const { t } = useI18n();
</script>

<template>
  <el-image
    v-if="imageUrl"
    class="order-product-image"
    :src="imageUrl"
    :preview-src-list="[imageUrl]"
    fit="cover"
    preview-teleported
    alt=""
    data-test="detail-product-image"
  />
  <span
    v-else
    class="order-product-image order-product-image--missing"
    :aria-label="t('order.itemImageMissing')"
    >-</span
  >
</template>

<style scoped>
.order-product-image {
  width: 40px;
  height: 48px;
  vertical-align: middle;
  flex-shrink: 0;
  border-radius: var(--tcrm-radius-tag);
}

.order-product-image--missing {
  display: inline-grid;
  place-items: center;
  color: var(--tcrm-color-text-secondary);
  background: var(--tcrm-color-muted);
}
</style>
