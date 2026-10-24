<script setup lang="ts">
import type { KolOrderItem } from "@/api/orders";

defineProps<{
  items: KolOrderItem[];
  amount: (value: string) => string;
  labels: {
    item: string;
    sku: string;
    quantity: string;
    payAmount: string;
    refundAmount: string;
  };
}>();
</script>

<template>
  <div class="item-list">
    <div class="item-list__head">
      <span>{{ labels.item }}</span
      ><span>{{ labels.sku }}</span
      ><span>{{ labels.quantity }}</span> <span>{{ labels.payAmount }}</span
      ><span>{{ labels.refundAmount }}</span>
    </div>
    <div v-for="(item, index) in items" :key="index" class="item-list__row">
      <span>{{ item.itemName }}</span
      ><span>{{ item.skuName || "—" }}</span> <span>{{ item.quantity }}</span
      ><span>{{ amount(item.payAmount) }}</span>
      <span>{{ amount(item.refundAmount) }}</span>
    </div>
  </div>
</template>

<style scoped>
.item-list {
  display: grid;
  gap: var(--tcrm-space-2);
}
.item-list__head,
.item-list__row {
  display: grid;
  grid-template-columns: 2fr 1.5fr 0.6fr 1fr 1fr;
  gap: var(--tcrm-space-2);
}
.item-list__head {
  font-weight: 600;
}
@media (max-width: 767px) {
  .item-list__head,
  .item-list__row {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    overflow-wrap: anywhere;
  }
}
</style>
