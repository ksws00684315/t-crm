<script setup lang="ts">
import { computed } from "vue";
import type { NotificationVO } from "@/api/notification/notification";
import NameCode from "@/components/NameCode.vue";
import { notificationValues } from "@/utils/notification";

/**
 * One notification sentence. When the message carries `kolName` (task 71, B06) the creator renders
 * as the shared two-line identity — name above, code below; messages without it keep the plain
 * inline code, which is also the shape of all history written before the field existed.
 */
defineOptions({ name: "NotificationText" });

const props = defineProps<{ item: NotificationVO }>();

const values = computed(() => notificationValues(props.item));
const kolName = computed(() => props.item.params?.kolName ?? null);
</script>

<template>
  <i18n-t
    :keypath="`notification.type.${item.type}`"
    scope="global"
    tag="span"
    class="notification-text"
    data-test="notification-text"
  >
    <template #kolCode>
      <NameCode
        v-if="kolName"
        class="notification-text__kol"
        :name="kolName"
        :code="item.params?.kolCode"
      />
      <template v-else>{{ values.kolCode }}</template>
    </template>
    <template #accountType>{{ values.accountType }}</template>
    <template #shopName>{{ values.shopName }}</template>
    <template #reason>{{ values.reason }}</template>
    <template #bdName>{{ values.bdName }}</template>
    <template #count>{{ values.count }}</template>
    <template #prepayNo>{{ values.prepayNo }}</template>
    <template #applicationNo>{{ values.applicationNo }}</template>
    <template #time>{{ values.time }}</template>
    <template #kind>{{ values.kind }}</template>
    <template #result>{{ values.result }}</template>
  </i18n-t>
</template>

<style scoped>
.notification-text {
  min-width: 0;
  overflow-wrap: anywhere;
}

.notification-text__kol {
  display: inline-flex;
  width: auto;
  vertical-align: middle;
}
</style>
