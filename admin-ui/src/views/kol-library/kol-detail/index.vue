<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onActivated, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import {
  deleteLibraryContent,
  deleteLibraryKol,
  getLibraryKol,
  listLibraryContents,
  type LibraryContentVO,
  type LibraryKolDetailVO
} from "@/api/kol-library/kol";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import { formatAmountWithCurrency } from "@/utils/format";
import { libraryOptionLabel, sampleTagType } from "@/utils/kol-library";
import { notifyError } from "@/utils/notify";
import { libraryAbilities } from "@/utils/permission";
import LibraryContentDialog from "./LibraryContentDialog.vue";
import LibraryFollowUpDialog from "./LibraryFollowUpDialog.vue";

defineOptions({ name: "LibraryKolDetailPage" });

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const tabs = useTabsStore();
const me = useUserStore();
const abilities = computed(() => libraryAbilities(me.user));

const id = computed(() => String(route.params.id ?? ""));
const loading = ref(false);
const loadFailed = ref(false);
const notFound = ref(false);
const detail = ref<LibraryKolDetailVO | null>(null);
const contents = ref<LibraryContentVO[]>([]);
const contentsLoading = ref(false);
const contentsFailed = ref(false);
const activeTab = ref<"profile" | "contents">("profile");

const contentDialogVisible = ref(false);
const editingContent = ref<LibraryContentVO | null>(null);
const followUpVisible = ref(false);

/** The path of this creator's detail tab, used by keep-alive and by the other pages. */
const selfPath = computed(() => `/kol-library/kols/${id.value}`);

function asContent(row: object): LibraryContentVO {
  return row as LibraryContentVO;
}

function show(value: string | number | null | undefined): string {
  return value === null || value === undefined || value === "" ? "—" : String(value);
}

function option(family: Parameters<typeof libraryOptionLabel>[0], value: string | null): string {
  return value ? libraryOptionLabel(family, value) : "—";
}

const metrics = computed(() => {
  const totals = detail.value?.totals;
  return [
    {
      key: "contentCount",
      label: t("library.metric.contentCount"),
      value: show(totals?.contentCount)
    },
    { key: "clicks", label: t("library.metric.clicks"), value: show(totals?.clicks) },
    { key: "orders", label: t("library.metric.orders"), value: show(totals?.orders) },
    { key: "gmv", label: t("library.metric.gmv"), value: money(totals?.gmv) },
    { key: "totalCost", label: t("library.metric.totalCost"), value: money(totals?.totalCost) },
    { key: "roi", label: t("library.metric.roi"), value: show(totals?.roi) }
  ];
});

/** Money in CNY with the ¥ sign and grouping; an empty value shows a dash. */
function money(value: string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  return formatAmountWithCurrency(value, "CNY");
}

/** One read-only field of the profile; a link opens in a new window, a wide one takes the row. */
interface ProfileField {
  label: string;
  value: string;
  link?: string | null;
  wide?: boolean;
}

interface ProfileGroup {
  key: string;
  title: string;
  fields: ProfileField[];
}

/** The profile groups of requirement 1.2.5 ③; short fields read in two columns (norm 13 §3.4). */
const profileGroups = computed<ProfileGroup[]>(() => {
  const row = detail.value;
  if (!row) return [];
  const groups: ProfileGroup[] = [
    {
      key: "basic",
      title: t("library.group.basic"),
      fields: [
        { label: t("library.field.kolUsername"), value: `@${row.kolUsername}` },
        { label: t("library.field.displayName"), value: row.displayName },
        { label: t("library.field.followers"), value: show(row.followers) },
        { label: t("library.field.platform"), value: option("platform", row.platform) },
        { label: t("library.field.country"), value: option("country", row.countryCode) },
        {
          label: t("library.field.profileUrl"),
          value: show(row.profileUrl),
          link: row.profileUrl
        },
        {
          label: t("library.field.categories"),
          value: row.categories
            .map(item => libraryOptionLabel("category", item))
            .join(t("library.separator"))
        },
        { label: t("library.field.avgViews"), value: show(row.avgViews) },
        { label: t("library.field.engagementRate"), value: show(row.engagementRate) }
      ]
    },
    {
      key: "audience",
      title: t("library.group.audience"),
      fields: [
        { label: t("library.field.audienceCountry"), value: show(row.audienceCountry) },
        {
          label: t("library.field.audienceAgeRange"),
          value: option("audienceAge", row.audienceAgeRange)
        },
        {
          label: t("library.field.audienceGender"),
          value: option("audienceGender", row.audienceGender)
        }
      ]
    },
    {
      key: "contact",
      title: t("library.group.contact"),
      fields: [{ label: t("library.field.contact"), value: show(row.contact) }]
    },
    {
      key: "quality",
      title: t("library.group.quality"),
      fields: [
        { label: t("library.field.contentQuality"), value: option("level", row.contentQuality) },
        { label: t("library.field.salesPotential"), value: option("level", row.salesPotential) },
        {
          label: t("library.field.cooperationAttitude"),
          value: option("level", row.cooperationAttitude)
        },
        { label: t("library.field.grade"), value: option("grade", row.grade) },
        {
          label: t("library.field.cooperationStage"),
          value: option("cooperationStage", row.cooperationStage)
        }
      ]
    },
    {
      key: "tags",
      title: t("library.group.tags"),
      fields: [
        {
          label: t("library.field.tags"),
          value: row.tags.length ? row.tags.join(t("library.separator")) : "—",
          wide: row.tags.length > 3
        },
        { label: t("library.field.remark"), value: show(row.remark), wide: true }
      ]
    },
    {
      key: "followUp",
      title: t("library.group.followUp"),
      fields: [
        {
          label: t("library.field.outreachStatus"),
          value: option("outreachStatus", row.outreachStatus)
        },
        { label: t("library.field.followUpOwner"), value: row.followUpOwner },
        { label: t("library.field.lastContactDate"), value: show(row.lastContactDate) },
        { label: t("library.field.sampleStatus"), value: option("sampleStatus", row.sampleStatus) },
        { label: t("library.field.trackingNo"), value: show(row.trackingNo) },
        { label: t("library.field.sampleDate"), value: show(row.sampleDate) }
      ]
    }
  ];
  return groups;
});

/** The content table in the order of requirement 1.2.5 ③; amounts are right aligned. */
const contentColumns = computed<DataColumn[]>(() => [
  { key: "publishDate", label: t("library.content.publishDate"), width: 120 },
  { key: "contentForm", label: t("library.content.contentForm"), width: 100 },
  {
    key: "publishUrl",
    align: "left" as const,
    label: t("library.content.publishUrl"),
    minWidth: 200
  },
  { key: "clicks", label: t("library.content.clicks"), amount: true, width: 100 },
  { key: "orders", label: t("library.content.orders"), amount: true, width: 90 },
  { key: "gmv", label: t("library.content.gmv"), amount: true, width: 130 },
  { key: "sampleType", label: t("library.content.sampleType"), width: 90 },
  { key: "sampleCost", label: t("library.content.sampleCost"), amount: true, width: 110 },
  { key: "slotFee", label: t("library.content.slotFee"), amount: true, width: 110 },
  { key: "commissionRate", label: t("library.content.commissionRate"), amount: true, width: 100 },
  {
    key: "estimatedCommission",
    label: t("library.content.estimatedCommission"),
    amount: true,
    width: 120
  },
  { key: "totalCost", label: t("library.content.totalCost"), amount: true, width: 120 },
  { key: "actualRoi", label: t("library.content.actualRoi"), amount: true, width: 100 },
  {
    key: "nextAction",
    align: "left" as const,
    label: t("library.content.nextAction"),
    minWidth: 160,
    tooltip: true
  },
  { key: "actions", label: t("common.actions"), width: 96 }
]);

function contentActions(row: LibraryContentVO): RowAction[] {
  return [
    {
      key: "edit",
      label: t("common.edit"),
      testId: `content-edit-${row.id}`,
      hidden: !abilities.value.canEdit,
      onClick: () => openContentDialog(row)
    },
    {
      key: "delete",
      label: t("common.delete"),
      type: "danger",
      testId: `content-delete-${row.id}`,
      hidden: !abilities.value.canDelete,
      onClick: () => void removeContent(row)
    }
  ];
}

async function load(): Promise<void> {
  if (!id.value) return;
  loading.value = true;
  loadFailed.value = false;
  notFound.value = false;
  try {
    const loaded = await getLibraryKol(id.value);
    detail.value = loaded;
    // The tab title carries the nickname (requirement 1.2.5 ③); a missing creator keeps the default.
    const tab = tabs.tabs.find(item => item.path === selfPath.value);
    if (tab && loaded.displayName) {
      tabs.open({
        ...tab,
        titleKey: loaded.displayName,
        literalTitle: true
      });
    }
  } catch (error) {
    detail.value = null;
    notFound.value = true;
    loadFailed.value = true;
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

async function loadContents(): Promise<void> {
  if (!id.value || !detail.value) return;
  contentsLoading.value = true;
  contentsFailed.value = false;
  try {
    contents.value = await listLibraryContents(id.value);
  } catch (error) {
    contentsFailed.value = true;
    notifyError(error);
  } finally {
    contentsLoading.value = false;
  }
}

/** The edit form opens as a tab, titled "edit {nickname}" (requirement 1.2.5 ④). */
function openEdit(): void {
  const path = `${selfPath.value}/edit`;
  const name = detail.value?.displayName;
  tabs.open({
    path,
    titleKey: name ? t("library.editTabTitle", { name }) : t("library.editTitle"),
    literalTitle: true,
    affix: false,
    componentName: "LibraryKolFormPage"
  });
  void router.push(path);
}

function openContentDialog(row: LibraryContentVO | null): void {
  editingContent.value = row;
  contentDialogVisible.value = true;
}

function openFollowUp(): void {
  followUpVisible.value = true;
}

async function onContentSaved(): Promise<void> {
  await Promise.all([load(), loadContents()]);
  activeTab.value = "contents";
}

async function onFollowUpSaved(): Promise<void> {
  await load();
}

async function removeContent(row: LibraryContentVO): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t("library.confirmDeleteContent", { date: row.publishDate }),
      t("library.deleteContentTitle"),
      {
        type: "warning",
        confirmButtonText: t("common.delete"),
        cancelButtonText: t("common.cancel")
      }
    );
  } catch {
    return;
  }
  try {
    await deleteLibraryContent(id.value, row.id);
    ElMessage.success(t("library.contentDeleted"));
    tabs.refresh("/kol-library/kols");
    await Promise.all([load(), loadContents()]);
  } catch (error) {
    notifyError(error);
  }
}

/** Deletes the creator: its tabs close and the list reloads (requirement 1.2.5 ③). */
async function removeKol(): Promise<void> {
  const row = detail.value;
  if (!row) return;
  try {
    await ElMessageBox.confirm(
      t("library.confirmDelete", { name: row.displayName }),
      t("library.deleteTitle"),
      {
        type: "warning",
        confirmButtonText: t("common.delete"),
        cancelButtonText: t("common.cancel")
      }
    );
  } catch {
    return;
  }
  try {
    await deleteLibraryKol(row.id);
    ElMessage.success(t("library.deleted"));
    for (const path of tabs.tabs
      .map(tab => tab.path)
      .filter(path => path === selfPath.value || path.startsWith(`${selfPath.value}/`))) {
      tabs.close(path);
    }
    tabs.refresh("/kol-library/kols");
    await router.push("/kol-library/kols");
  } catch (error) {
    notifyError(error);
  }
}

function backToList(): void {
  void router.push("/kol-library/kols");
}

watch(id, () => {
  activeTab.value = "profile";
  void load();
  void loadContents();
});

// A tab restored from the tab list is cached by KeepAlive, so leaving and coming back does not run
// onMounted again: (re)load whenever the creator is shown, which also covers the return from an
// edit. Reading the id on every activation is cheap; the requests are the ones onMounted made.
onActivated(() => {
  void load();
  void loadContents();
});

onMounted(() => {
  void load();
  void loadContents();
});
</script>

<template>
  <div class="detail-page" data-test="library-detail">
    <el-empty
      v-if="notFound && !loading"
      class="detail-page__empty"
      data-test="library-detail-empty"
      :description="t('library.notFound')"
    >
      <el-button type="primary" data-test="library-back" @click="backToList">
        {{ t("library.backToList") }}
      </el-button>
    </el-empty>

    <template v-else>
      <!-- Header card: who the creator is and what the relationship is (requirement 1.2.5 ③). -->
      <el-card v-loading="loading" shadow="never" class="detail-page__header-card">
        <div class="header-card">
          <div class="header-card__main">
            <div class="header-card__title">
              <h1 class="header-card__name" data-test="detail-name">
                {{ detail?.displayName ?? "" }}
              </h1>
              <CopyText v-if="detail" :value="`@${detail.kolUsername}`" />
            </div>
            <div v-if="detail" class="header-card__meta">
              <el-tag size="small" type="info">{{ option("platform", detail.platform) }}</el-tag>
              <el-tag size="small" type="info">{{ option("country", detail.countryCode) }}</el-tag>
              <el-tag
                v-for="category in detail.categories"
                :key="category"
                size="small"
                data-test="detail-category"
              >
                {{ libraryOptionLabel("category", category) }}
              </el-tag>
            </div>
            <dl v-if="detail" class="header-card__facts">
              <div>
                <dt>{{ t("library.field.grade") }}</dt>
                <dd>{{ option("grade", detail.grade) }}</dd>
              </div>
              <div>
                <dt>{{ t("library.field.cooperationStage") }}</dt>
                <dd>{{ option("cooperationStage", detail.cooperationStage) }}</dd>
              </div>
              <div>
                <dt>{{ t("library.field.outreachStatus") }}</dt>
                <dd>{{ option("outreachStatus", detail.outreachStatus) }}</dd>
              </div>
              <div>
                <dt>{{ t("library.field.followUpOwner") }}</dt>
                <dd>{{ detail.followUpOwner }}</dd>
              </div>
              <div>
                <dt>{{ t("library.field.sampleStatus") }}</dt>
                <dd>
                  <el-tag size="small" :type="sampleTagType(detail.sampleStatus)">
                    {{ option("sampleStatus", detail.sampleStatus) }}
                  </el-tag>
                </dd>
              </div>
            </dl>
          </div>
          <div v-if="detail" class="header-card__actions">
            <el-button v-if="abilities.canEdit" data-test="detail-edit" @click="openEdit">
              {{ t("library.editProfile") }}
            </el-button>
            <el-button v-if="abilities.canEdit" data-test="detail-follow-up" @click="openFollowUp">
              {{ t("library.updateFollowUp") }}
            </el-button>
            <el-button
              v-if="abilities.canDelete"
              type="danger"
              plain
              data-test="detail-delete"
              @click="removeKol"
            >
              {{ t("common.delete") }}
            </el-button>
          </div>
        </div>
      </el-card>

      <el-tabs v-model="activeTab" class="detail-page__tabs">
        <el-tab-pane :label="t('library.tab.profile')" name="profile">
          <div class="profile-groups">
            <el-card
              v-for="group in profileGroups"
              :key="group.key"
              shadow="never"
              class="profile-group"
              :data-test="`group-${group.key}`"
            >
              <template #header>{{ group.title }}</template>
              <dl class="profile-group__fields">
                <div
                  v-for="field in group.fields"
                  :key="field.label"
                  class="profile-field"
                  :class="{ 'profile-field--wide': field.wide }"
                >
                  <dt>{{ field.label }}</dt>
                  <dd>
                    <a
                      v-if="field.link"
                      :href="field.link"
                      target="_blank"
                      rel="noopener noreferrer"
                      data-test="profile-link"
                      >{{ field.value }}</a
                    >
                    <span v-else>{{ field.value }}</span>
                  </dd>
                </div>
              </dl>
            </el-card>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="t('library.tab.contents')" name="contents">
          <div class="metrics" data-test="library-metrics">
            <div v-for="metric in metrics" :key="metric.key" class="metric-card">
              <span class="metric-card__label">{{ metric.label }}</span>
              <span class="metric-card__value" :data-test="`metric-${metric.key}`">
                {{ metric.value }}
              </span>
            </div>
          </div>
          <!-- The actions live above the table: a plain table has no toolbar of its own. -->
          <div class="contents-toolbar">
            <span class="data-table__total">{{
              t("table.total", { total: contents.length })
            }}</span>
            <el-button
              v-if="abilities.canEdit"
              type="primary"
              data-test="content-create"
              @click="openContentDialog(null)"
            >
              {{ t("library.content.create") }}
            </el-button>
          </div>
          <DataTable
            table-id="library-contents"
            plain
            :rows="contents"
            :columns="contentColumns"
            :loading="contentsLoading"
            :failed="contentsFailed"
            :total="contents.length"
            :paged="false"
            @refresh="loadContents"
            @retry="loadContents"
          >
            <template #cell-contentForm="{ row }">
              {{ option("contentForm", asContent(row).contentForm) }}
            </template>
            <template #cell-publishUrl="{ row }">
              <div class="content-link">
                <a
                  :href="asContent(row).publishUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  data-test="content-link"
                  >{{ asContent(row).publishUrl }}</a
                >
                <CopyText :value="asContent(row).publishUrl" />
              </div>
            </template>
            <template #cell-gmv="{ row }">{{ money(asContent(row).gmv) }}</template>
            <template #cell-sampleType="{ row }">
              {{
                asContent(row).sampleType ? option("sampleType", asContent(row).sampleType) : "—"
              }}
            </template>
            <template #cell-sampleCost="{ row }">{{ money(asContent(row).sampleCost) }}</template>
            <template #cell-slotFee="{ row }">{{ money(asContent(row).slotFee) }}</template>
            <template #cell-commissionRate="{ row }">
              {{ asContent(row).commissionRate ?? "—" }}
            </template>
            <template #cell-estimatedCommission="{ row }">
              {{ money(asContent(row).estimatedCommission) }}
            </template>
            <template #cell-totalCost="{ row }">{{ money(asContent(row).totalCost) }}</template>
            <template #cell-actualRoi="{ row }">
              {{ asContent(row).actualRoi ?? "—" }}
            </template>
            <template #cell-nextAction="{ row }">
              {{ asContent(row).nextAction || "—" }}
            </template>
            <template #actions="{ row }">
              <RowActions :actions="contentActions(asContent(row))" />
            </template>
          </DataTable>
        </el-tab-pane>
      </el-tabs>
    </template>

    <LibraryContentDialog
      v-if="detail"
      v-model="contentDialogVisible"
      :kol-id="detail.id"
      :content="editingContent"
      @saved="onContentSaved"
    />
    <LibraryFollowUpDialog
      v-if="detail"
      v-model="followUpVisible"
      :detail="detail"
      @saved="onFollowUpSaved"
    />
  </div>
</template>

<style scoped>
/* Block layout, like the list and form pages: the route host is a scrolling flex container, and a
   column flex page there is squeezed to its own content height (the header collapsed to 2px). The
   gaps between sections are margins for the same reason. */
.detail-page {
  min-width: 0;
}
.detail-page > * + * {
  margin-top: var(--tcrm-space-4);
}
.header-card {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--tcrm-space-4);
}
.header-card__main {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.header-card__title {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--tcrm-space-2);
}
.header-card__name {
  margin: 0;
}
.header-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-1);
}
.header-card__facts {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--tcrm-space-2) var(--tcrm-space-4);
  margin: 0;
}
.header-card__facts dt,
.profile-field dt {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
}
.header-card__facts dd,
.profile-field dd {
  margin: 0;
  word-break: break-word;
}
.header-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.profile-groups {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
}
/* Short fields read in two columns (norm 13 §3.4); long values take the whole row. */
.profile-group__fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--tcrm-space-3) var(--tcrm-space-6);
  margin: 0;
}
.profile-field--wide {
  grid-column: 1 / -1;
}
.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--tcrm-space-3);
  margin-bottom: var(--tcrm-space-4);
}
.metric-card {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-1);
  padding: var(--tcrm-space-3);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.metric-card__label {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
}
.metric-card__value {
  font-weight: 600;
  font-size: var(--tcrm-font-size-title);
}
.contents-toolbar {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
}
.content-link {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-1);
  min-width: 0;
}
.content-link a {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
