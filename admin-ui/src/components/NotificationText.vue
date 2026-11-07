<script setup lang="ts">
import { computed } from "vue";
import type { NotificationVO } from "@/api/notification/notification";
import { notificationKey, notificationValues } from "@/utils/notification";
import { formatNameWithCode } from "@/utils/format";

/**
 * One notification sentence. The creator renders inline as "name（code）" (task 126): the two-line
 * identity used in tables cannot sit inside a sentence — it broke the line into three and left the
 * code reading as the subject (smoke item 29). Messages without a stored name keep the plain code,
 * which is also the shape of all history written before the field existed.
 */
defineOptions({ name: "NotificationText" });

const props = defineProps<{ item: NotificationVO }>();

const values = computed(() => notificationValues(props.item));
const kolIdentity = computed(() => {
  const name = props.item.params?.kolName?.trim();
  const code = props.item.params?.kolCode?.trim();
  if (!name) return code ?? "";
  return code && code !== name ? formatNameWithCode(name, code) : name;
});
</script>

<template>
  <i18n-t
    :keypath="notificationKey(item.type)"
    scope="global"
    tag="span"
    class="notification-text"
    data-test="notification-text"
  >
    <template #kolCode>{{ kolIdentity }}</template>
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
</style>
