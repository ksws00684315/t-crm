<script setup lang="ts">
import { Tools } from "@element-plus/icons-vue";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { translate } from "@/locales";

defineOptions({ name: "ComingSoonPage" });

/**
 * Shared placeholder for pages the owner put in the menu before their feature exists
 * (task 32 §2.1). One component backs every route; the title comes from the route meta so
 * each menu entry keeps its own name and tab.
 */
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const title = computed(() => (route.meta.title ? translate(String(route.meta.title)) : ""));
function backToWorkbench(): void {
  void router.push("/home");
}
</script>

<template>
  <div class="coming-soon" data-test="coming-soon">
    <el-icon
      class="coming-soon__icon"
      style="font-size: var(--tcrm-font-size-title)"
      aria-hidden="true"
      ><Tools
    /></el-icon>
    <h1 class="coming-soon__heading">{{ title }}</h1>
    <p class="coming-soon__text">{{ t("common.comingSoon") }}</p>
    <el-button data-test="coming-soon-home" @click="backToWorkbench">
      {{ t("common.backToWorkbench") }}
    </el-button>
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
.coming-soon__icon {
  color: var(--tcrm-color-text-secondary);
}
/* Size the svg directly: Element Plus sets the svg width inline. */
.coming-soon__icon :deep(svg) {
  width: var(--tcrm-font-size-title);
  height: var(--tcrm-font-size-title);
}
.coming-soon__heading {
  margin: 0;
}
.coming-soon__text {
  margin: 0;
  color: var(--tcrm-color-text-secondary);
}
</style>
