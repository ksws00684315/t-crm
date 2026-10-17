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
