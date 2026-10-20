<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute } from "vue-router";
import { translate } from "@/locales";

defineOptions({ name: "ComingSoonPage" });

/**
 * Shared placeholder for pages the owner put in the menu before their feature exists
 * (task 32 §2.1). One component backs every route; the title comes from the route meta so
 * each menu entry keeps its own name and tab.
 */
const { t } = useI18n();
const route = useRoute();
const title = computed(() => (route.meta.title ? translate(String(route.meta.title)) : ""));
</script>

<template>
  <div class="coming-soon" data-test="coming-soon">
    <h2 class="coming-soon__title">{{ title }}</h2>
    <p class="coming-soon__text">{{ t("common.comingSoon") }}</p>
  </div>
</template>

<style scoped>
.coming-soon {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--tcrm-space-2);
  flex: 1;
  min-height: 320px;
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.coming-soon__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--tcrm-color-text);
}
.coming-soon__text {
  margin: 0;
  color: var(--tcrm-color-text-secondary);
}
</style>
