<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { getKolItems, type KolItem } from "@/api/items";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch from "@/components/ListSearch.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { copyText } from "@/utils/copy";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "ItemsPage" });

const { t, locale } = useI18n();
const items = ref<KolItem[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(readPageSize("kol-items", 20));
const keyword = ref("");
const appliedKeyword = ref("");
const loading = ref(false);
const error = ref(false);
const copied = ref<string | null>(null);

const columns = computed<DataColumn[]>(() => [
  { key: "item", align: "left" as const, label: t("items.colItem"), minWidth: 240 },
  { key: "weidianItemId", align: "left" as const, label: t("items.colId"), minWidth: 160 },
  { key: "createTime", label: t("items.colCreated"), minWidth: 140 },
  { key: "actions", label: t("common.actions"), width: 120 }
]);

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
      size: size.value
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

function reset() {
  keyword.value = "";
  search();
}

function changePage(value: number) {
  page.value = value;
  void loadItems();
}

function changeSize(value: number) {
  size.value = value;
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

function rowActions(item: KolItem): RowAction[] {
  return [
    {
      key: "copy",
      label: copied.value === item.weidianItemId ? t("items.copied") : t("items.copyLink"),
      testId: "item-copy",
      onClick: () => void copyLink(item)
    }
  ];
}

onMounted(loadItems);
</script>

<template>
  <section class="items-page">
    <ListSearch @search="search" @reset="reset">
      <template #primary>
        <el-form-item :label="t('items.searchLabel')">
          <el-input
            v-model="keyword"
            type="search"
            clearable
            maxlength="128"
            :placeholder="t('items.searchPlaceholder')"
            :aria-label="t('items.searchPlaceholder')"
            data-test="items-keyword"
          />
        </el-form-item>
      </template>
    </ListSearch>

    <el-alert v-if="error" type="error" :title="t('items.loadFailed')" :closable="false" />

    <DataTable
      table-id="kol-items"
      class="items-table"
      :rows="items"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="page"
      :size="size"
      row-key="weidianItemId"
      @update:page="changePage"
      @update:size="changeSize"
      @refresh="loadItems"
    >
      <template #cell-item="{ row }">
        <div class="item-name">
          <img
            v-if="row.imageUrl"
            class="item-thumb"
            :src="row.imageUrl"
            :alt="row.itemName ?? row.weidianItemId"
            loading="lazy"
          />
          <span v-else class="item-thumb item-thumb--empty" aria-hidden="true">{{
            (row.itemName ?? row.weidianItemId).slice(0, 1)
          }}</span>
          <span class="item-name__text">{{ row.itemName ?? t("items.unnamed") }}</span>
        </div>
      </template>
      <template #cell-weidianItemId="{ row }">
        <CopyText :value="row.weidianItemId" />
      </template>
      <template #cell-createTime="{ row }">{{ dateLabel(row.createTime) }}</template>
      <template #actions="{ row }">
        <RowActions :actions="rowActions(row)" />
      </template>
      <template #empty>
        <div class="empty-state" data-test="items-empty">
          <p>{{ t("items.empty") }}</p>
          <p class="empty-hint">{{ t("items.emptyHint") }}</p>
        </div>
      </template>
    </DataTable>

    <!-- Phones show the same items as cards; the table is desktop-only. -->
    <ul v-if="items.length" class="item-list">
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
    <!-- The table's empty slot is hidden with the table on phones; the card list needs its own. -->
    <div v-else-if="!loading" class="empty-state items-empty-mobile" data-test="items-empty-mobile">
      <p>{{ t("items.empty") }}</p>
      <p class="empty-hint">{{ t("items.emptyHint") }}</p>
    </div>
    <el-pagination
      class="items-pager"
      :current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next"
      @current-change="changePage"
    />
  </section>
</template>

<style scoped>
.items-page {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  min-width: 0;
}
.empty-state {
  padding: var(--tcrm-space-6) var(--tcrm-space-4);
  color: var(--el-text-color-secondary);
  text-align: center;
}
/* Shown only where the card list replaces the table (see the media query below). */
.items-empty-mobile {
  display: none;
}
.empty-state p {
  margin: 0;
}
.empty-hint {
  margin-top: var(--tcrm-space-2);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
.item-name {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-3);
  min-width: 0;
}
.item-name__text {
  min-width: 0;
  overflow-wrap: anywhere;
}
.item-thumb {
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: var(--tcrm-radius-tag);
  object-fit: cover;
  background: var(--el-fill-color-light);
}
.item-thumb--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  font-weight: 600;
}
.item-list,
.items-pager {
  display: none;
}
.item-list {
  gap: var(--tcrm-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}
.item-card {
  display: flex;
  gap: var(--tcrm-space-3);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  padding: var(--tcrm-space-3);
}
.item-card__image {
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: var(--tcrm-radius-tag);
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
  gap: var(--tcrm-space-2);
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
  gap: var(--tcrm-space-3);
  color: var(--el-text-color-secondary);
}
.item-card__id {
  font-variant-numeric: tabular-nums;
}
.item-card__copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  justify-self: start;
  padding: var(--tcrm-space-2) var(--tcrm-space-3);
  border: 1px solid var(--el-color-primary);
  border-radius: var(--tcrm-radius-control);
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
}
.item-card__copy:hover {
  background: var(--el-color-primary-light-9);
}
@media (max-width: 767px) {
  .items-page .items-table {
    display: none;
  }
  .item-list {
    display: grid;
  }
  .items-empty-mobile {
    display: block;
  }
  .item-card__copy {
    min-height: var(--tcrm-control-height-mobile);
  }
  .items-pager {
    display: flex;
    justify-content: center;
  }
}
</style>
