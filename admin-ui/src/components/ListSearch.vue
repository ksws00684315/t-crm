<script setup lang="ts">
import { ref } from "vue";
import { useI18n } from "vue-i18n";

defineOptions({ name: "ListSearch" });

export interface SearchChip {
  key: string;
  label: string;
  text: string;
}

withDefaults(
  defineProps<{
    chips?: SearchChip[];
    hasMore?: boolean;
  }>(),
  { chips: () => [], hasMore: false }
);

const emit = defineEmits<{
  search: [];
  reset: [];
  remove: [key: string];
}>();

const { t } = useI18n();
const expanded = ref(false);
</script>

<template>
  <section class="list-search">
    <el-form class="list-search__form" @submit.prevent="emit('search')">
      <div class="list-search__row">
        <slot name="primary" />
        <div class="list-search__actions">
          <el-button
            type="primary"
            native-type="button"
            data-test="search"
            @click="emit('search')"
            >{{ t("common.search") }}</el-button
          >
          <el-button native-type="button" data-test="reset" @click="emit('reset')">{{
            t("common.reset")
          }}</el-button>
          <el-button
            v-if="hasMore"
            text
            data-test="search-expand"
            native-type="button"
            @click="expanded = !expanded"
          >
            {{ expanded ? t("search.collapse") : t("search.expand") }}
          </el-button>
          <slot name="actions" />
        </div>
      </div>
      <div v-show="expanded" class="list-search__row" data-test="search-more">
        <slot name="more" />
      </div>
    </el-form>
    <div v-if="chips.length" class="list-search__chips">
      <el-tag
        v-for="chip in chips"
        :key="chip.key"
        closable
        data-test="search-chip"
        @close="emit('remove', chip.key)"
      >
        {{ t("search.chip", { label: chip.label, text: chip.text }) }}
      </el-tag>
      <el-button text data-test="search-clear" @click="emit('reset')">
        {{ t("search.clearAll") }}
      </el-button>
    </div>
  </section>
</template>

<style scoped>
.list-search {
  margin-bottom: var(--tcrm-space-3);
}
.list-search__form :deep(.el-form-item) {
  margin-right: var(--tcrm-space-3);
  margin-bottom: var(--tcrm-space-2);
}
.list-search__row,
.list-search__actions,
.list-search__chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.list-search__chips {
  margin-top: var(--tcrm-space-2);
}
</style>
