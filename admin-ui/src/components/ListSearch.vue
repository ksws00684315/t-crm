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

/** HTML will not submit a form that has several text inputs and no submit button. */
function onEnter(event: KeyboardEvent): void {
  const target = event.target;
  if (!(target instanceof HTMLElement)) return;
  if (target.tagName === "TEXTAREA" || target.closest("textarea")) return;
  if (target.closest("button, [role='button']")) return;
  emit("search");
}
</script>

<template>
  <section class="list-search">
    <el-form class="list-search__form" @submit.prevent="emit('search')" @keyup.enter="onEnter">
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
          <div v-if="$slots.actions" class="list-search__extra">
            <slot name="actions" />
          </div>
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
  padding: var(--tcrm-space-4);
  margin-bottom: 0;
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.list-search__form :deep(.el-form-item) {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  margin: 0;
}
.list-search__form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: var(--tcrm-space-1);
  line-height: var(--tcrm-line-height-body);
}
.list-search__form :deep(.el-form-item__content) {
  width: 100%;
  min-width: 0;
  margin-left: 0 !important;
}
.list-search__row,
.list-search__actions,
.list-search__chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.list-search__row {
  column-gap: var(--tcrm-space-4);
  row-gap: var(--tcrm-space-3);
}
.list-search__actions {
  flex: 1;
  align-self: flex-end;
  min-width: min(100%, 180px);
}
.list-search__extra {
  display: flex;
  margin-left: auto;
}
.list-search__chips {
  margin-top: var(--tcrm-space-3);
}
</style>
