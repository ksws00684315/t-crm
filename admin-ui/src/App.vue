<script setup lang="ts">
import { computed, watch } from "vue";
import { ElConfigProvider } from "element-plus";
import en from "element-plus/es/locale/lang/en";
import zhCN from "element-plus/es/locale/lang/zh-cn";
import { useRoute } from "vue-router";
import VersionNotice from "@/components/VersionNotice.vue";
import { applyDocumentTitle } from "@/router/title";
import { useLocaleStore } from "@/stores/locale";
import type { LocaleId } from "@/utils/locale";
import { siteName } from "@/stores/site-config";

defineOptions({ name: "AppRoot" });

const elementLocales: Record<LocaleId, typeof en> = {
  "en-US": en,
  "zh-CN": zhCN
};

const localeStore = useLocaleStore();
const elementLocale = computed(() => elementLocales[localeStore.locale]);

// The router sets the title on navigation; a language switch updates it without navigating again.
const route = useRoute();
watch(
  () => [localeStore.locale, siteName.value],
  () => applyDocumentTitle(route.meta)
);
</script>

<template>
  <ElConfigProvider :locale="elementLocale">
    <RouterView />
    <VersionNotice />
  </ElConfigProvider>
</template>
