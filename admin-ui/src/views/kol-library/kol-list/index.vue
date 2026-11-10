<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import {
  listLibraryKols,
  type LibraryCategory,
  type LibraryCountry,
  type LibraryKolQuery,
  type LibraryKolVO,
  type LibraryOutreachStatus,
  type LibraryPlatform
} from "@/api/kol-library/kol";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import {
  LIBRARY_CATEGORIES,
  LIBRARY_COUNTRIES,
  LIBRARY_OUTREACH_STATUSES,
  LIBRARY_PLATFORMS,
  libraryOptionLabel,
  outreachTagType
} from "@/utils/kol-library";
import { notifyError } from "@/utils/notify";
import { libraryAbilities } from "@/utils/permission";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "LibraryKolListPage" });

const { t, locale } = useI18n();
const router = useRouter();
const me = useUserStore();
const tabs = useTabsStore();
/** Widths are tuned for the 14px Chinese labels; English needs more room and scrolls instead. */
const px = (zh: number, en: number): number => (locale.value === "zh-CN" ? zh : en);
const abilities = computed(() => libraryAbilities(me.user));

const loading = ref(false);
const loadFailed = ref(false);
const rows = ref<LibraryKolVO[]>([]);
const total = ref(0);
const query = reactive<{
  page: number;
  size: number;
  keyword: string;
  platform?: LibraryPlatform;
  countryCode?: LibraryCountry;
  category?: LibraryCategory;
  outreachStatus?: LibraryOutreachStatus;
  followUpOwner: string;
}>({
  page: 1,
  size: readPageSize("library-kols", 20),
  keyword: "",
  followUpOwner: ""
});

function asKol(row: object): LibraryKolVO {
  return row as LibraryKolVO;
}

/** Followers with thousands separators; the same grouping in both languages. */
const followersFormat = new Intl.NumberFormat("en-US");
function followers(value: number | string | null): string {
  return value === null || value === "" ? "—" : followersFormat.format(Number(value));
}

/**
 * The eight columns of requirement 1.2.5 ② as revised on 2026-11-10, in this order. Followers is
 * a quantity and stays centered with tabular figures (norm 13 appendix A).
 */
const columns = computed<DataColumn[]>(() => [
  { key: "kol", align: "left" as const, label: t("library.column.kol"), minWidth: px(200, 240) },
  { key: "platform", label: t("library.column.platform"), width: px(100, 110) },
  { key: "followers", label: t("library.column.followers"), amount: true, width: px(110, 120) },
  { key: "countryCode", label: t("library.column.country"), width: px(100, 130) },
  {
    key: "categories",
    align: "left" as const,
    label: t("library.column.categories"),
    minWidth: px(200, 260)
  },
  { key: "outreachStatus", label: t("library.column.outreachStatus"), width: px(120, 150) },
  {
    key: "followUpOwner",
    align: "left" as const,
    label: t("library.column.followUpOwner"),
    prop: "followUpOwner",
    minWidth: px(120, 150)
  },
  { key: "grade", label: t("library.column.grade"), width: px(100, 130) },
  { key: "actions", label: t("common.actions"), width: px(112, 112) }
]);

const applied = ref({
  keyword: "",
  platform: undefined as LibraryPlatform | undefined,
  countryCode: undefined as LibraryCountry | undefined,
  category: undefined as LibraryCategory | undefined,
  outreachStatus: undefined as LibraryOutreachStatus | undefined,
  followUpOwner: ""
});

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  const value = applied.value;
  if (value.keyword) {
    list.push({ key: "keyword", label: t("library.filter.keyword"), text: value.keyword });
  }
  if (value.platform) {
    list.push({
      key: "platform",
      label: t("library.field.platform"),
      text: libraryOptionLabel("platform", value.platform)
    });
  }
  if (value.countryCode) {
    list.push({
      key: "countryCode",
      label: t("library.field.country"),
      text: libraryOptionLabel("country", value.countryCode)
    });
  }
  if (value.category) {
    list.push({
      key: "category",
      label: t("library.field.categories"),
      text: libraryOptionLabel("category", value.category)
    });
  }
  if (value.outreachStatus) {
    list.push({
      key: "outreachStatus",
      label: t("library.field.outreachStatus"),
      text: libraryOptionLabel("outreachStatus", value.outreachStatus)
    });
  }
  if (value.followUpOwner) {
    list.push({
      key: "followUpOwner",
      label: t("library.field.followUpOwner"),
      text: value.followUpOwner
    });
  }
  return list;
});

function appliedQuery(): LibraryKolQuery {
  const value = applied.value;
  return {
    keyword: value.keyword || undefined,
    platform: value.platform,
    countryCode: value.countryCode,
    category: value.category,
    outreachStatus: value.outreachStatus,
    followUpOwner: value.followUpOwner || undefined
  };
}

async function load(): Promise<void> {
  loading.value = true;
  loadFailed.value = false;
  try {
    const page = await listLibraryKols({ page: query.page, size: query.size, ...appliedQuery() });
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    loadFailed.value = true;
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function snapshotFilters(): void {
  applied.value = {
    keyword: query.keyword.trim(),
    platform: query.platform,
    countryCode: query.countryCode,
    category: query.category,
    outreachStatus: query.outreachStatus,
    followUpOwner: query.followUpOwner.trim()
  };
}

function search(): void {
  query.page = 1;
  snapshotFilters();
  void load();
}

function reset(): void {
  query.keyword = "";
  query.platform = undefined;
  query.countryCode = undefined;
  query.category = undefined;
  query.outreachStatus = undefined;
  query.followUpOwner = "";
  search();
}

function removeChip(key: string): void {
  if (key === "keyword") query.keyword = "";
  if (key === "platform") query.platform = undefined;
  if (key === "countryCode") query.countryCode = undefined;
  if (key === "category") query.category = undefined;
  if (key === "outreachStatus") query.outreachStatus = undefined;
  if (key === "followUpOwner") query.followUpOwner = "";
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

/** The creator detail opens as an in-app tab; opening the same creator again switches to it. */
async function openDetail(row: LibraryKolVO): Promise<void> {
  const path = `/kol-library/kols/${row.id}`;
  // The tab title carries the nickname; the page resolves it again on its own load.
  if (!tabs.tabs.some(tab => tab.path === path)) {
    tabs.open({
      path,
      titleKey: row.displayName,
      literalTitle: true,
      affix: false,
      componentName: "LibraryKolDetailPage"
    });
  }
  await router.push(path);
}

function openCreate(): void {
  void router.push("/kol-library/kols/new");
}

/** Edit opens the same form as Add, filled with the creator (requirement 1.2.5 ②, 2026-11-11). */
async function openEdit(row: LibraryKolVO): Promise<void> {
  const path = `/kol-library/kols/${row.id}/edit`;
  if (!tabs.tabs.some(tab => tab.path === path)) {
    tabs.open({
      path,
      titleKey: t("library.editTabTitle", { name: row.displayName }),
      literalTitle: true,
      affix: false,
      componentName: "LibraryKolFormPage"
    });
  }
  await router.push(path);
}

function rowActions(row: LibraryKolVO): RowAction[] {
  return [
    {
      key: "edit",
      label: t("common.edit"),
      testId: `library-edit-${row.id}`,
      hidden: !abilities.value.canEdit,
      onClick: () => void openEdit(row)
    },
    {
      key: "view",
      label: t("library.view"),
      testId: `library-view-${row.id}`,
      onClick: () => void openDetail(row)
    }
  ];
}

onMounted(load);
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" has-more @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('library.filter.keyword')">
          <el-input
            v-model="query.keyword"
            clearable
            maxlength="128"
            data-test="library-keyword"
            :placeholder="t('library.filter.keywordPlaceholder')"
          />
        </el-form-item>
      </template>
      <template #more>
        <el-form-item :label="t('library.field.platform')">
          <el-select
            v-model="query.platform"
            clearable
            :placeholder="t('common.all')"
            data-test="library-platform"
            class="filter-w-sm"
          >
            <el-option
              v-for="option in LIBRARY_PLATFORMS"
              :key="option.value"
              :label="libraryOptionLabel('platform', option.value)"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('library.field.country')">
          <el-select
            v-model="query.countryCode"
            clearable
            :placeholder="t('common.all')"
            data-test="library-country"
            class="filter-w-sm"
          >
            <el-option
              v-for="option in LIBRARY_COUNTRIES"
              :key="option.value"
              :label="libraryOptionLabel('country', option.value)"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('library.field.categories')">
          <el-select
            v-model="query.category"
            clearable
            :placeholder="t('common.all')"
            data-test="library-category"
            class="filter-w-sm"
          >
            <el-option
              v-for="option in LIBRARY_CATEGORIES"
              :key="option.value"
              :label="libraryOptionLabel('category', option.value)"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('library.field.outreachStatus')">
          <el-select
            v-model="query.outreachStatus"
            clearable
            :placeholder="t('common.all')"
            data-test="library-outreach-status"
            class="filter-w-sm"
          >
            <el-option
              v-for="option in LIBRARY_OUTREACH_STATUSES"
              :key="option.value"
              :label="libraryOptionLabel('outreachStatus', option.value)"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('library.field.followUpOwner')">
          <el-input
            v-model="query.followUpOwner"
            clearable
            maxlength="64"
            data-test="library-follow-up-owner"
            class="filter-w-sm"
          />
        </el-form-item>
      </template>
      <template #actions>
        <el-button
          v-if="abilities.canEdit"
          type="primary"
          data-test="library-create"
          @click="openCreate"
        >
          {{ t("library.create") }}
        </el-button>
      </template>
    </ListSearch>

    <DataTable
      table-id="library-kols"
      fill-available-height
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :failed="loadFailed"
      :total="total"
      :page="query.page"
      :size="query.size"
      :filter-count="chips.length"
      @update:page="onPage"
      @update:size="onSize"
      @refresh="load"
      @retry="load"
      @clear-filters="reset"
    >
      <template #cell-kol="{ row }">
        <div class="kol-cell">
          <span class="kol-cell__name" data-test="kol-name">{{ asKol(row).displayName }}</span>
          <CopyText :value="`@${asKol(row).kolUsername}`" />
        </div>
      </template>
      <template #cell-platform="{ row }">
        {{ libraryOptionLabel("platform", asKol(row).platform) }}
      </template>
      <template #cell-followers="{ row }">{{ followers(asKol(row).followers) }}</template>
      <template #cell-countryCode="{ row }">
        {{ libraryOptionLabel("country", asKol(row).countryCode) }}
      </template>
      <template #cell-categories="{ row }">
        <div class="category-cell">
          <el-tag
            v-for="category in asKol(row).categories"
            :key="category"
            size="small"
            type="info"
            data-test="kol-category"
          >
            {{ libraryOptionLabel("category", category) }}
          </el-tag>
        </div>
      </template>
      <template #cell-outreachStatus="{ row }">
        <el-tag size="small" :type="outreachTagType(asKol(row).outreachStatus)">
          {{ libraryOptionLabel("outreachStatus", asKol(row).outreachStatus) }}
        </el-tag>
      </template>
      <template #cell-grade="{ row }">
        {{ asKol(row).grade ? libraryOptionLabel("grade", asKol(row).grade) : "—" }}
      </template>
      <template #actions="{ row }">
        <RowActions :actions="rowActions(asKol(row))" />
      </template>
    </DataTable>
  </div>
</template>

<style scoped>
/* The nickname on the first line, the copyable @username under it. */
.kol-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-width: 0;
}
.kol-cell__name {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.category-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>
