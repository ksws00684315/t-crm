<script setup lang="ts">
import { useI18n } from "vue-i18n";
import { useLocaleStore } from "@/stores/locale";
import { SUPPORTED_LOCALES, type LocaleId } from "@/utils/locale";

defineOptions({ name: "LocaleSwitch" });

const { t } = useI18n();
const localeStore = useLocaleStore();

function select(value: LocaleId): void {
  localeStore.setLocale(value);
}
</script>

<template>
  <el-select
    :model-value="localeStore.locale"
    :aria-label="t('common.language')"
    class="locale-switch"
    size="small"
    @update:model-value="select"
  >
    <el-option v-for="id in SUPPORTED_LOCALES" :key="id" :value="id" :label="t(`locale.${id}`)" />
  </el-select>
</template>

<style scoped>
.locale-switch {
  width: 108px;
}
</style>
