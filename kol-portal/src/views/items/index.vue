<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getKolItems, type KolItem } from "@/api/items";
import { copyText } from "@/utils/copy";

defineOptions({ name: "ItemsPage" });

const { t, locale } = useI18n();
const items = ref<KolItem[]>([]);
const total = ref(0);
const page = ref(1);
const size = 20;
const keyword = ref("");
const appliedKeyword = ref("");
const loading = ref(false);
const error = ref(false);
const copied = ref<string | null>(null);

function dateLabel(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString(locale.value);
}

async function loadItems() {
  loading.value = true;
  error.value = false;
  try {
    const result = await getKolItems({
      ...(appliedKeyword.value ? { keyword: appliedKeyword.value } : {}),
      page: page.value,
      size
    });
    items.value = result.items;
    total.value = Number(result.total);
  } catch {
    error.value = true;
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  appliedKeyword.value = keyword.value.trim();
  void loadItems();
}

async function copyLink(item: KolItem) {
  const ok = await copyText(item.transitUrl);
  if (ok) {
    copied.value = item.weidianItemId;
    setTimeout(() => {
      if (copied.value === item.weidianItemId) {
        copied.value = null;
      }
    }, 2000);
  }
}

watch(page, loadItems);
onMounted(loadItems);
</script>

<template>
  <section class="items-page">
    <h1>{{ t("items.title") }}</h1>
    <form class="search" @submit.prevent="search">
      <input
        v-model="keyword"
        type="search"
        maxlength="128"
        :placeholder="t('items.searchPlaceholder')"
        :aria-label="t('items.searchPlaceholder')"
        data-test="items-keyword"
      />
      <button type="submit" data-test="items-search">{{ t("items.search") }}</button>
    </form>

    <el-alert v-if="error" type="error" :title="t('items.loadFailed')" :closable="false" />
    <div v-else-if="!loading && total === 0" class="empty-state" data-test="items-empty">
      <p>{{ t("items.empty") }}</p>
      <p class="empty-hint">{{ t("items.emptyHint") }}</p>
    </div>
    <el-skeleton v-else-if="loading" :rows="4" animated />

    <template v-else>
      <ul class="item-list">
        <li v-for="item in items" :key="item.weidianItemId" class="item-card" data-test="item-card">
          <img
            v-if="item.imageUrl"
            class="item-card__image"
            :src="item.imageUrl"
            :alt="item.itemName ?? item.weidianItemId"
            loading="lazy"
          />
          <div v-else class="item-card__image item-card__image--empty" aria-hidden="true">
            {{ (item.itemName ?? item.weidianItemId).slice(0, 1) }}
          </div>
          <div class="item-card__body">
            <p class="item-card__name">{{ item.itemName ?? t("items.unnamed") }}</p>
            <p class="item-card__meta">
              <span class="item-card__id">{{ item.weidianItemId }}</span>
              <span>{{ dateLabel(item.createTime) }}</span>
            </p>
            <button
              class="item-card__copy"
              type="button"
              data-test="item-copy"
              @click="copyLink(item)"
            >
              {{ copied === item.weidianItemId ? t("items.copied") : t("items.copyLink") }}
            </button>
          </div>
        </li>
      </ul>

      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
      />
    </template>
  </section>
</template>

<style scoped>
.items-page {
  min-width: 0;
}
.items-page h1 {
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  margin: 0 0 16px;
}
.search {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.search input {
  flex: 1;
  min-width: 0;
  max-width: 360px;
  padding: 8px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}
.search button {
  padding: 8px 16px;
  border: 0;
  border-radius: 6px;
  background: var(--el-color-primary);
  color: var(--el-color-white);
  cursor: pointer;
}
.empty-state {
  padding: 48px 16px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.empty-state p {
  margin: 0;
}
.empty-hint {
  margin-top: 8px;
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
.item-list {
  display: grid;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.item-card {
  display: flex;
  gap: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px;
}
.item-card__image {
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: 6px;
  object-fit: cover;
  background: var(--el-fill-color-light);
}
.item-card__image--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--tcrm-font-size-section);
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.item-card__body {
  min-width: 0;
  flex: 1;
  display: grid;
  gap: 6px;
}
.item-card__name {
  margin: 0;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.item-card__meta {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-body);
}
.item-card__id {
  font-variant-numeric: tabular-nums;
}
.item-card__copy {
  justify-self: start;
  padding: 6px 12px;
  border: 1px solid var(--el-color-primary);
  border-radius: 6px;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
}
.item-card__copy:hover {
  background: var(--el-color-primary-light-9);
}
.el-pagination {
  justify-content: center;
  margin-top: 20px;
}
@media (max-width: 390px) {
  .search {
    flex-direction: column;
  }
  .search input,
  .search button {
    max-width: none;
    width: 100%;
  }
}
</style>
