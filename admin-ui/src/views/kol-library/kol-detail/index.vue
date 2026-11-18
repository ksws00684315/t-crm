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
import { libraryOptionLabel, outreachTagType, sampleTagType } from "@/utils/kol-library";
import { notifyError } from "@/utils/notify";
import { libraryAbilities } from "@/utils/permission";
import LibraryWeeklyDrawer from "./LibraryWeeklyDrawer.vue";
import LibraryContentDialog from "./LibraryContentDialog.vue";
import LibraryFollowUpDialog from "./LibraryFollowUpDialog.vue";

defineOptions({ name: "LibraryKolDetailPage" });

const { t, locale } = useI18n();
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
const weeklyContent = ref<LibraryContentVO | null>(null);
const weeklyOpen = ref(false);
const editingContent = ref<LibraryContentVO | null>(null);
const followUpVisible = ref(false);
const deleting = ref(false);

/** The path of this creator's detail tab, used by keep-alive and by the other pages. */
const selfPath = computed(() => `/kol-library/kols/${id.value}`);

const initials = computed(() => {
  const name = (detail.value?.displayName || detail.value?.kolUsername || "").trim();
  const words = name.split(/\s+/);
  const letters =
    words.length > 1
      ? words.slice(0, 2).map(word => Array.from(word)[0])
      : Array.from(name).slice(0, 2);
  return letters.join("").toUpperCase();
});

const countFormat = new Intl.NumberFormat("en-US");

function audienceValue(label: string, share: string | null | undefined): string {
  if (share == null || label === "—") return label;
  const value = Number(share).toLocaleString(locale.value, { maximumFractionDigits: 2 });
  return locale.value.startsWith("zh") ? `${label}（${value}%）` : `${label} (${value}%)`;
}

function formatCount(value: string | number | null): string {
  if (typeof value === "number") return countFormat.format(value);
  return value && /^\d+$/.test(value) ? countFormat.format(BigInt(value)) : show(value);
}

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
      value: formatCount(totals?.contentCount ?? null)
    },
    {
      key: "clicks",
      label: t("library.metric.clicks"),
      value: formatCount(totals?.clicks ?? null)
    },
    {
      key: "orders",
      label: t("library.metric.orders"),
      value: formatCount(totals?.orders ?? null)
    },
    { key: "gmv", label: t("library.metric.gmv"), value: money(totals?.gmv) },
    {
      key: "totalCost",
      label: t("library.metric.totalCost"),
      value: money(totals?.totalCost),
      breakdown:
        totals?.commissionCost != null && totals.sampleCost != null && totals.slotFee != null
          ? t("library.metric.costBreakdown", {
              commission: money(totals.commissionCost).replace(/^¥/, ""),
              sample: money(totals.sampleCost).replace(/^¥/, ""),
              slot: money(totals.slotFee).replace(/^¥/, "")
            })
          : undefined
    },
    { key: "roi", label: t("library.metric.roi"), value: show(totals?.roi) }
  ];
});

function formatLibraryEntryDate(created: string): string {
  const date = new Date(created);
  if (Number.isNaN(date.getTime())) return "—";
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

/** Money in CNY with the ¥ sign and grouping; an empty value shows a dash. */
function money(value: string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  return formatAmountWithCurrency(value, "CNY");
}

/** One read-only field of the profile; a link opens in a new window, a wide one takes the row. */
interface ProfileField {
  label: string;
  value?: string;
  link?: string | null;
  tags?: string[];
  tagType?: ReturnType<typeof outreachTagType>;
  wide?: boolean;
  note?: boolean;
}

interface ProfileGroup {
  key: string;
  title: string;
  fields: ProfileField[];
}

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
        { label: t("library.field.followers"), value: formatCount(row.followers) },
        { label: t("library.field.platform"), value: option("platform", row.platform) },
        { label: t("library.field.country"), value: option("country", row.countryCode) },
        {
          label: t("library.field.profileUrl"),
          value: show(row.profileUrl),
          link: row.profileUrl
        },
        {
          label: t("library.field.categories"),
          tags: row.categories.map(item => libraryOptionLabel("category", item)),
          tagType: "primary"
        },
        { label: t("library.field.avgViews"), value: formatCount(row.avgViews) },
        {
          label: t("library.overview.engagementRate"),
          value: row.engagementRate ? `${row.engagementRate}%` : "—"
        },
        { label: t("library.field.contact"), value: show(row.contact) },
        {
          label: t("library.field.tags"),
          tags: row.tags,
          tagType: "info",
          wide: true
        },
        { label: t("library.field.remark"), value: show(row.remark), note: true }
      ]
    },
    {
      key: "audience",
      title: t("library.group.audience"),
      fields: [
        {
          label: t("library.field.audienceCountry"),
          value: option("country", row.audienceCountry)
        },
        {
          label: t("library.field.audienceAgeRange"),
          value: audienceValue(option("audienceAge", row.audienceAgeRange), row.audienceAgeShare)
        },
        {
          label: t("library.field.audienceGender"),
          value: audienceValue(
            option("audienceGender", row.audienceGender),
            row.audienceGenderShare
          )
        }
      ]
    },
    {
      key: "followUp",
      title: t("library.group.followUp"),
      fields: [
        {
          label: t("library.field.libraryEntryDate"),
          value: formatLibraryEntryDate(row.createTime)
        },
        {
          label: t("library.field.outreachStatus"),
          tags: [option("outreachStatus", row.outreachStatus)],
          tagType: outreachTagType(row.outreachStatus)
        },
        { label: t("library.field.followUpOwner"), value: row.followUpOwner },
        { label: t("library.field.lastContactDate"), value: show(row.lastContactDate) },
        {
          label: t("library.field.sampleStatus"),
          tags: [option("sampleStatus", row.sampleStatus)],
          tagType: sampleTagType(row.sampleStatus)
        },
        {
          label: t("library.field.sampleType"),
          value: option("sampleType", row.sampleType ?? null)
        },
        { label: t("library.field.trackingNo"), value: show(row.trackingNo) },
        { label: t("library.field.sampleDate"), value: show(row.sampleDate) },
        { label: t("library.field.followUpRemark"), value: show(row.followUpRemark), note: true }
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
  { key: "sampleCost", label: t("library.content.sampleCost"), amount: true, width: 110 },
  { key: "slotFee", label: t("library.content.slotFee"), amount: true, width: 110 },
  {
    key: "commissionRate",
    label: t("library.content.commissionRateColumn"),
    amount: true,
    width: 100
  },
  {
    key: "estimatedCommission",
    label: t("library.content.estimatedCommission"),
    amount: true,
    width: 120
  },
  { key: "totalCost", label: t("library.content.totalCost"), amount: true, width: 120 },
  { key: "actualRoi", label: t("library.content.actualRoi"), amount: true, width: 100 },
  { key: "actions", label: t("common.actions"), width: 190 }
]);

function contentActions(row: LibraryContentVO): RowAction[] {
  return [
    {
      key: "view",
      label: t("library.content.viewTitle"),
      testId: `content-view-${row.id}`,
      onClick: () => openContentDialog(row)
    },
    {
      key: "weekly",
      label: t("library.weekly.title"),
      testId: `content-weekly-${row.id}`,
      onClick: () => {
        weeklyContent.value = row;
        weeklyOpen.value = true;
      }
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
        titleKey: t("library.detailTabTitle", { name: loaded.displayName }),
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

/**
 * Loads the profile and then its contents. The contents need the profile (they are skipped while
 * it is missing), so the two are never started side by side: on a fresh mount the profile is not
 * there yet and the table would stay empty for good.
 */
async function loadAll(): Promise<void> {
  await load();
  await loadContents();
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
  await loadAll();
  if (weeklyContent.value) {
    weeklyContent.value =
      contents.value.find(row => row.id === weeklyContent.value?.id) ?? weeklyContent.value;
  }
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
    await loadAll();
  } catch (error) {
    notifyError(error);
  }
}

function backToList(): void {
  void router.push("/kol-library/kols");
}

async function removeKol(): Promise<void> {
  const creator = detail.value;
  if (!creator || creator.id !== id.value || !abilities.value.canDelete || deleting.value) return;
  deleting.value = true;
  const creatorPath = selfPath.value;
  const isCreatorTab = (path: string) => path === creatorPath || path === `${creatorPath}/edit`;
  const name = creator.displayName.trim() || creator.kolUsername;
  try {
    const confirmed = await ElMessageBox.confirm(
      t("library.confirmDelete", { name }),
      t("library.deleteTitle"),
      {
        type: "warning",
        confirmButtonText: t("common.delete"),
        cancelButtonText: t("common.cancel")
      }
    ).then(
      () => true,
      () => false
    );
    if (!confirmed) return;
    await deleteLibraryKol(creator.id);
    ElMessage.success(t("library.deleted"));
    for (const tab of tabs.tabs.filter(tab => isCreatorTab(tab.path))) {
      tabs.close(tab.path);
    }
    tabs.refresh("/kol-library/kols");
    if (isCreatorTab(route.path)) backToList();
  } catch (error) {
    notifyError(error);
  } finally {
    deleting.value = false;
  }
}

watch(id, () => {
  activeTab.value = "profile";
  void loadAll();
});

// A tab restored from the tab list is cached by KeepAlive, so leaving and coming back does not run
// onMounted again: (re)load whenever the creator is shown, which also covers the return from an
// edit. Reading the id on every activation is cheap; the requests are the ones onMounted made.
onActivated(() => {
  void loadAll();
});

onMounted(() => {
  void loadAll();
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
      <el-card v-loading="loading" shadow="never" class="detail-page__header-card">
        <template v-if="detail">
          <div class="header-card__heading">
            <div class="header-card__identity">
              <el-avatar :size="56" class="header-card__avatar" aria-hidden="true">
                {{ initials }}
              </el-avatar>
              <div>
                <h1 class="header-card__name" data-test="detail-name">{{ detail.displayName }}</h1>
                <CopyText :value="`@${detail.kolUsername}`" />
              </div>
            </div>
            <div v-if="abilities.canEdit || abilities.canDelete" class="header-card__actions">
              <el-button
                v-if="abilities.canEdit"
                data-test="detail-follow-up"
                @click="openFollowUp"
              >
                {{ t("library.updateFollowUp") }}
              </el-button>
              <el-button
                v-if="abilities.canEdit"
                type="primary"
                plain
                data-test="detail-edit"
                @click="openEdit"
              >
                {{ t("library.editProfile") }}
              </el-button>
              <el-button
                v-if="abilities.canDelete"
                type="danger"
                plain
                :loading="deleting"
                data-test="detail-delete"
                @click="removeKol"
              >
                {{ t("library.deleteTitle") }}
              </el-button>
            </div>
          </div>
          <div class="header-card__meta">
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
          <div class="header-card__summary">
            <section class="header-card__panel">
              <h2>{{ t("library.overview.account") }}</h2>
              <dl class="header-card__rows">
                <div>
                  <dt>{{ t("library.field.followers") }}</dt>
                  <dd class="header-card__followers">{{ formatCount(detail.followers) }}</dd>
                </div>
                <div>
                  <dt>{{ t("library.field.avgViews") }}</dt>
                  <dd>{{ formatCount(detail.avgViews) }}</dd>
                </div>
                <div>
                  <dt>{{ t("library.overview.engagementRate") }}</dt>
                  <dd>{{ detail.engagementRate ? `${detail.engagementRate}%` : "—" }}</dd>
                </div>
              </dl>
            </section>
            <section class="header-card__panel">
              <h2>{{ t("library.overview.quality") }}</h2>
              <dl class="header-card__quality" data-test="detail-quality">
                <div>
                  <dt>{{ t("library.field.contentQuality") }}</dt>
                  <dd>{{ option("level", detail.contentQuality) }}</dd>
                </div>
                <div>
                  <dt>{{ t("library.field.salesPotential") }}</dt>
                  <dd>{{ option("level", detail.salesPotential) }}</dd>
                </div>
                <div>
                  <dt>{{ t("library.field.cooperationAttitude") }}</dt>
                  <dd>{{ option("level", detail.cooperationAttitude) }}</dd>
                </div>
                <div>
                  <dt>{{ t("library.field.grade") }}</dt>
                  <dd>
                    <el-tag v-if="detail.grade" size="small">{{
                      option("grade", detail.grade)
                    }}</el-tag>
                    <span v-else>—</span>
                  </dd>
                </div>
              </dl>
            </section>
            <section class="header-card__panel">
              <h2>{{ t("library.overview.audience") }}</h2>
              <dl class="header-card__rows">
                <div>
                  <dt>{{ t("library.field.audienceCountry") }}</dt>
                  <dd>{{ option("country", detail.audienceCountry) }}</dd>
                </div>
                <div>
                  <dt>{{ t("library.field.audienceAgeRange") }}</dt>
                  <dd>
                    {{
                      audienceValue(
                        option("audienceAge", detail.audienceAgeRange),
                        detail.audienceAgeShare
                      )
                    }}
                  </dd>
                </div>
                <div>
                  <dt>{{ t("library.field.audienceGender") }}</dt>
                  <dd>
                    {{
                      audienceValue(
                        option("audienceGender", detail.audienceGender),
                        detail.audienceGenderShare
                      )
                    }}
                  </dd>
                </div>
              </dl>
            </section>
            <section class="header-card__panel">
              <h2>{{ t("library.overview.status") }}</h2>
              <dl class="header-card__rows">
                <div>
                  <dt>{{ t("library.field.outreachStatus") }}</dt>
                  <dd>
                    <el-tag size="small" :type="outreachTagType(detail.outreachStatus)">
                      {{ option("outreachStatus", detail.outreachStatus) }}
                    </el-tag>
                  </dd>
                </div>
                <div>
                  <dt>{{ t("library.field.followUpOwner") }}</dt>
                  <dd>{{ show(detail.followUpOwner) }}</dd>
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
            </section>
          </div>
        </template>
      </el-card>

      <el-tabs v-model="activeTab" class="detail-page__tabs">
        <el-tab-pane :label="t('library.tab.profile')" name="profile">
          <el-card
            v-if="profileGroups.length"
            shadow="never"
            class="profile-groups"
            :body-style="{ padding: 0 }"
          >
            <section
              v-for="group in profileGroups"
              :key="group.key"
              class="profile-group"
              :class="`profile-group--${group.key}`"
              :data-test="`group-${group.key}`"
            >
              <h2 class="profile-group__title">{{ group.title }}</h2>
              <dl class="profile-group__fields">
                <div
                  v-for="field in group.fields"
                  :key="field.label"
                  class="profile-field"
                  :class="{
                    'profile-field--wide': field.wide || field.note,
                    'profile-field--note': field.note
                  }"
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
                    <div v-else-if="field.tags?.length" class="profile-field__tags">
                      <el-tag
                        v-for="tag in field.tags"
                        :key="tag"
                        size="small"
                        :type="field.tagType"
                      >
                        {{ tag }}
                      </el-tag>
                    </div>
                    <span
                      v-else
                      :class="{ 'profile-field__empty': !field.value || field.value === '—' }"
                    >
                      {{ field.value ?? "—" }}
                    </span>
                  </dd>
                </div>
              </dl>
            </section>
          </el-card>
        </el-tab-pane>

        <el-tab-pane :label="t('library.tab.contents')" name="contents">
          <div class="metrics" data-test="library-metrics">
            <div v-for="metric in metrics" :key="metric.key" class="metric-card">
              <span class="metric-card__label">{{ metric.label }}</span>
              <span class="metric-card__value" :data-test="`metric-${metric.key}`">
                {{ metric.value }}
              </span>
              <small
                v-if="metric.breakdown"
                class="metric-card__breakdown"
                data-test="cost-breakdown"
              >
                {{ metric.breakdown }}
              </small>
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
              </div>
            </template>
            <template #cell-gmv="{ row }">{{ money(asContent(row).gmv) }}</template>
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
      :can-edit="abilities.canEdit"
      @saved="onContentSaved"
    />
    <LibraryWeeklyDrawer
      v-if="detail && weeklyContent"
      v-model="weeklyOpen"
      :creator="detail"
      :content="weeklyContent"
      :can-edit="abilities.canEdit"
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
.detail-page__header-card {
  container-type: inline-size;
}
.header-card__heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-4);
}
.header-card__identity {
  display: flex;
  align-items: center;
  flex: 1 1 220px;
  gap: var(--tcrm-space-3);
  min-width: 0;
}
.header-card__identity > div {
  min-width: 0;
}
.header-card__avatar {
  flex-shrink: 0;
  background: var(--tcrm-color-option-selected-bg);
  color: var(--tcrm-color-primary-text);
  font-size: var(--tcrm-font-size-section);
  font-weight: 700;
}
.header-card__name {
  margin: 0 0 var(--tcrm-space-1);
  overflow-wrap: anywhere;
}
.header-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
  margin-top: var(--tcrm-space-3);
}
.header-card__summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--tcrm-space-3);
  margin-top: var(--tcrm-space-4);
}
.header-card__panel {
  min-width: 0;
  padding: var(--tcrm-space-4);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-page);
}
.header-card__panel h2 {
  margin: 0 0 var(--tcrm-space-2);
  font-size: var(--tcrm-font-size-body);
  font-weight: 650;
}
.header-card__panel dl {
  margin: 0;
}
.header-card__rows > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--tcrm-space-2);
  padding-block: var(--tcrm-space-2);
}
.header-card__rows > div + div {
  border-top: 1px solid var(--tcrm-color-border);
}
.header-card__rows dd {
  min-width: 0;
  text-align: end;
}
.header-card__rows :deep(.el-tag) {
  height: auto;
  min-height: 24px;
  max-width: 100%;
  white-space: normal;
}
.header-card__followers {
  font-size: var(--tcrm-font-size-brand);
  line-height: 1;
}
.header-card__quality {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--tcrm-space-2) var(--tcrm-space-3);
  padding-top: var(--tcrm-space-2);
}
@container (max-width: 900px) {
  .header-card__summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@container (max-width: 530px) {
  .header-card__summary {
    grid-template-columns: minmax(0, 1fr);
  }
}
.header-card__panel dt,
.profile-field dt {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
}
.header-card__panel dd,
.profile-field dd {
  margin: 0;
  word-break: break-word;
}
.header-card__panel dd {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.header-card__panel .header-card__quality dd {
  margin-top: var(--tcrm-space-1);
}
.header-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.header-card__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.profile-groups {
  container-type: inline-size;
}
.profile-group {
  padding: var(--tcrm-space-4) var(--tcrm-space-5);
}
.profile-group + .profile-group {
  border-top: 1px solid var(--tcrm-color-border);
}
.profile-group__title {
  margin: 0 0 var(--tcrm-space-2);
  padding-inline-start: var(--tcrm-space-2);
  border-inline-start: 3px solid var(--tcrm-color-primary);
  font-size: var(--tcrm-font-size-section);
  font-weight: 600;
}
.profile-group__fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  column-gap: var(--tcrm-space-6);
  margin: 0;
}
.profile-group--basic .profile-group__fields {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.profile-field {
  display: grid;
  grid-template-columns: minmax(0, min(38%, 160px)) minmax(0, 1fr);
  align-items: baseline;
  gap: var(--tcrm-space-2);
  padding-block: var(--tcrm-space-2);
  border-bottom: 1px solid var(--tcrm-color-border);
}
.profile-group--audience .profile-field {
  border-bottom: 0;
}
.profile-field--wide {
  grid-column: 1 / -1;
  grid-template-columns: minmax(88px, min(18%, 160px)) minmax(0, 1fr);
}
.profile-field--note {
  margin-top: var(--tcrm-space-2);
  padding: var(--tcrm-space-2) var(--tcrm-space-3);
  border: 0;
  border-radius: var(--tcrm-radius-control);
  background: var(--tcrm-color-page);
}
.profile-field--note dd {
  white-space: pre-wrap;
}
.profile-field dd {
  min-width: 0;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}
.profile-field__empty {
  color: var(--tcrm-color-text-secondary);
}
.profile-field__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-1);
}
.profile-field__tags :deep(.el-tag) {
  max-width: 100%;
  height: auto;
  min-height: 24px;
  white-space: normal;
}
@container (max-width: 740px) {
  .profile-group__fields {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: var(--tcrm-space-4);
  }
  .profile-field {
    grid-template-columns: minmax(0, 45%) minmax(0, 1fr);
  }
  .profile-field--wide {
    grid-template-columns: minmax(88px, 22%) minmax(0, 1fr);
  }
}
@container (max-width: 470px) {
  .profile-group {
    padding-inline: var(--tcrm-space-3);
  }
  .profile-group__fields,
  .profile-group--basic .profile-group__fields {
    grid-template-columns: minmax(0, 1fr);
  }
  .profile-field--wide {
    grid-template-columns: minmax(0, 45%) minmax(0, 1fr);
  }
}
.metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--tcrm-space-3);
  margin-bottom: var(--tcrm-space-4);
}
.metric-card {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-1);
  min-width: 0;
  padding: var(--tcrm-space-4);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}
.metric-card__breakdown {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: 18px;
  overflow-wrap: anywhere;
}
.metric-card__label {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-body);
}
.metric-card__value {
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
  line-height: var(--tcrm-line-height-title);
  font-weight: 600;
  font-size: var(--tcrm-font-size-title);
}
.contents-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
  margin-bottom: var(--tcrm-space-3);
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

<style scoped>
@media (min-width: 1600px) {
  .metrics {
    grid-template-columns: repeat(6, minmax(0, 1fr));
  }
}
@media (max-width: 767px) {
  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 420px) {
  .metrics {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
