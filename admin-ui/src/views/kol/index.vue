<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { getCleanupRemaining, updateKolStatus } from "@/api/kol/kol";
import type { DisplayRelationStatus, KolVO, ReviewStatus } from "@/api/types";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { useLocaleStore } from "@/stores/locale";
import { useUserStore } from "@/stores/user";
import { formatInstantToMinute } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { accessOf, kolAbilities } from "@/utils/permission";
import { relationStatusLabelKey, relationTagType } from "@/utils/promotion";
import { formatRate } from "@/utils/rate";
import { readPageSize } from "@/utils/table-prefs";
import { listKols } from "@/api/kol/kol";
import CleanupConfigDialog from "./CleanupConfigDialog.vue";
import CommissionDialog from "./CommissionDialog.vue";
import InviteKolDialog from "./InviteKolDialog.vue";
import KolManageTab from "./KolManageTab.vue";
import KolProfileDialog from "./KolProfileDialog.vue";
import KolFundDialog from "./KolFundDialog.vue";

defineOptions({ name: "KolListPage" });

/**
 * The creator list after the owner's 2026-10-20 review (task 32 §2.2): the layout follows the original
 * system, the columns and row actions follow the latest customer supplement. The super
 * administrator also gets the creator-management tab that transfers creators between BDs; the
 * transfer history itself lives in the audit log.
 */
const { t } = useI18n();
const me = useUserStore();
const abilities = computed(() => kolAbilities(me.user));
const isSuperAdmin = computed(() => accessOf(me.user).isSuperAdmin);
/** An own-scoped BD: the list only shows their creators plus exact-search finds. */
const isBd = computed(() => accessOf(me.user).isOwnScope);

const loading = ref(false);
const rows = ref<KolVO[]>([]);
const total = ref(0);
const cleanupRemaining = ref<Record<string, number>>({});
const activeTab = ref("list");

/**
 * 03.02 combined query: creator ID, name and email, ANDed by the backend (2026-10-20 review).
 */
const query = reactive({
  page: 1,
  size: readPageSize("kol", 20),
  kolId: "",
  name: "",
  email: ""
});

const applied = ref({ kolId: "", name: "", email: "" });

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  const current = applied.value;
  if (current.kolId) list.push({ key: "kolId", label: t("kol.kolId"), text: current.kolId });
  if (current.name) list.push({ key: "name", label: t("kol.kolName"), text: current.name });
  if (current.email) list.push({ key: "email", label: t("kol.email"), text: current.email });
  return list;
});

async function load() {
  loading.value = true;
  try {
    const page = await listKols({
      page: query.page,
      size: query.size,
      kolCode: applied.value.kolId || undefined,
      name: applied.value.name || undefined,
      email: applied.value.email || undefined
    });
    rows.value = page.items;
    total.value = Number(page.total);
    const configured = page.items.filter(row => row.autoCleanupDays != null).map(row => row.id);
    cleanupRemaining.value = configured.length
      ? await getCleanupRemaining(configured).catch(() => ({}))
      : {};
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function search() {
  applied.value = {
    kolId: query.kolId.trim(),
    name: query.name.trim(),
    email: query.email.trim()
  };
  query.page = 1;
  void load();
}

function reset() {
  query.kolId = "";
  query.name = "";
  query.email = "";
  search();
}

function removeChip(key: string): void {
  if (key === "kolId") query.kolId = "";
  if (key === "name") query.name = "";
  if (key === "email") query.email = "";
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

/** A creator on this BD's own list (03.02): everything except edit and review needs this. */
function rowOwned(row: KolVO): boolean {
  if (!isBd.value) return true;
  return row.bdUserId === me.user?.id;
}

/**
 * Widths are measured against the rendered 14px text; emails, phone numbers and social handles
 * ellipsize with a tooltip either way (the visual guide allows that as long as the tooltip shows
 * the rest). Every 04.01 column shows by default (owner decision 2026-10-24, task 59 review); at
 * 1440 the roster overflows the card (zh 1558px, en 1492px measured against ~1158px) and scrolls
 * horizontally inside it — pinned first and action columns cast the DataTable fixed-column shadow.
 * The ADR-029 action column widths (344/476) are unchanged.
 */
const zhWidth = computed(() => useLocaleStore().locale === "zh-CN");
const px = (zh: number, en: number): number => (zhWidth.value ? zh : en);

const columns = computed<DataColumn[]>(() => [
  { key: "kol", align: "left" as const, label: t("copyToken.kol"), minWidth: 170 },
  // 140px is what the literal column name from requirement 04.01 ("Phone/WhatsApp") needs with the
  // 10px cell padding; the header itself was ellipsized at the old 112px. The email column gives up
  // those pixels: its values ellipsize with a tooltip either way.
  {
    key: "contact",
    align: "left" as const,
    label: t("kol.phoneWhatsapp"),
    width: px(170, 180),
    tooltip: true
  },
  {
    key: "email",
    align: "left" as const,
    label: t("kol.email"),
    width: px(104, 168),
    tooltip: true
  },
  {
    key: "platform",
    align: "left" as const,
    label: t("kol.primaryPlatform"),
    width: px(160, 176),
    tooltip: true
  },
  { key: "rate", label: t("kol.defaultRate"), width: px(80, 104), amount: true },
  // en datetime "10/20/2026, 00:15" measured 138px of content; 136 clipped it (task 59).
  { key: "inviteTime", label: t("kol.inviteTime"), width: px(146, 142), tooltip: true },
  {
    key: "inviter",
    align: "left" as const,
    label: t("kol.inviter"),
    width: px(80, 104),
    tooltip: true
  },
  { key: "review", label: t("kol.reviewStatus"), width: px(80, 116) },
  { key: "login", label: t("kol.loginStatus"), width: px(80, 112) },
  // en status tag measured 164px of content; 124 clipped it (task 59 measurement).
  { key: "relation", label: t("kol.relationStatus"), width: px(80, 168) },
  { key: "actions", label: t("common.actions"), width: px(344, 476) }
]);

function asKol(row: object): KolVO {
  return row as KolVO;
}

function reviewStatusText(status?: ReviewStatus): string {
  return status ? t(`kol.reviewStatusOption.${status}`) : "-";
}

function loginStatusText(status?: KolVO["loginStatus"]): string {
  return status ? t(`kol.loginStatusOption.${status}`) : "-";
}

function relationStatusText(status?: DisplayRelationStatus): string {
  return status ? t(relationStatusLabelKey(status)) : "-";
}

/** Phone first; WhatsApp only when there is no phone; "-" when neither (04.01). */
function contactText(row: KolVO): string {
  return row.phone || row.whatsapp || "-";
}

function platformText(row: KolVO): string {
  const primary = row.socialAccounts.find(account => account.primary);
  if (!primary) return "-";
  return `${primary.platform} / ${primary.handle}`;
}

const profileVisible = ref(false);
const reviewMode = ref(false);
const profileKol = ref<KolVO | null>(null);
const commissionVisible = ref(false);
const commissionKol = ref<KolVO | null>(null);
const cleanupVisible = ref(false);
const cleanupKol = ref<KolVO | null>(null);
const inviteVisible = ref(false);
const fundVisible = ref(false);
const fundKol = ref<KolVO | null>(null);

/** Funds read the creator's payout accounts, so they need the reviewer's full-account permission. */
function openFund(row: KolVO) {
  fundKol.value = row;
  fundVisible.value = true;
}

function openEdit(row: KolVO) {
  profileKol.value = row;
  reviewMode.value = false;
  profileVisible.value = true;
}

function openReview(row: KolVO) {
  profileKol.value = row;
  reviewMode.value = true;
  profileVisible.value = true;
}

function openCommission(row: KolVO) {
  commissionKol.value = row;
  commissionVisible.value = true;
}

function openCleanup(row: KolVO) {
  cleanupKol.value = row;
  cleanupVisible.value = true;
}

function cleanupLabel(row: KolVO): string {
  if (row.autoCleanupDays == null) return t("kol.actionCleanup");
  const remaining = cleanupRemaining.value[row.id] ?? row.autoCleanupDays;
  return t("kol.cleanupRemaining", { days: remaining });
}

/** All five actions stay inline in the 04.01 order (owner decision 2026-10-21, ADR-029). */
function rowActions(row: KolVO): RowAction[] {
  return [
    {
      key: "edit",
      label: t("kol.actionEdit"),
      testId: "action-edit",
      disabled: !abilities.value.canWrite,
      onClick: () => openEdit(row)
    },
    {
      key: "commission",
      label: t("kol.actionCommission"),
      testId: "action-commission",
      disabled: !(abilities.value.canWrite && row.reviewStatus === "APPROVED" && rowOwned(row)),
      onClick: () => openCommission(row)
    },
    {
      key: "fund",
      label: t("kol.actionFund"),
      testId: `action-fund-${row.id}`,
      disabled: !(accessOf(me.user).can("payout-account:review") && rowOwned(row)),
      onClick: () => openFund(row)
    },
    {
      key: "cleanup",
      label: cleanupLabel(row),
      testId: `action-cleanup-${row.id}`,
      danger: row.autoCleanupDays != null,
      disabled: !(abilities.value.canWrite && rowOwned(row)),
      onClick: () => openCleanup(row)
    },
    {
      key: "freeze",
      label: row.status === 1 ? t("kol.freeze") : t("kol.unfreeze"),
      testId: `action-freeze-${row.id}`,
      type: row.status === 1 ? "danger" : "success",
      disabled: !(abilities.value.canWrite && rowOwned(row)),
      onClick: () => toggleStatus(row)
    }
  ];
}

function reviewClickable(row: KolVO): boolean {
  return abilities.value.canWrite && row.reviewStatus === "PENDING";
}

async function toggleStatus(row: KolVO) {
  const target = row.status === 1 ? 0 : 1;
  const disabling = target === 0;
  try {
    await ElMessageBox.confirm(
      disabling
        ? t("kol.confirmFreeze", { code: row.kolCode, name: row.name })
        : t("kol.confirmUnfreeze", { code: row.kolCode, name: row.name }),
      disabling ? t("kol.freezeTitle") : t("kol.unfreezeTitle"),
      { type: "warning" }
    );
  } catch {
    return; // cancelled
  }
  try {
    await updateKolStatus(row.id, target);
    ElMessage.success(disabling ? t("kol.frozen") : t("kol.unfrozen"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <div class="list-page">
    <el-tabs v-model="activeTab" class="kol-tabs">
      <el-tab-pane :label="t('kol.listTab')" name="list">
        <ListSearch :chips="chips" @search="search" @reset="reset" @remove="removeChip">
          <template #primary>
            <el-form-item :label="t('kol.kolId')">
              <el-input
                v-model="query.kolId"
                clearable
                maxlength="16"
                data-test="filter-kol-id"
                :placeholder="t('kol.idPlaceholder')"
              />
            </el-form-item>
            <el-form-item :label="t('kol.kolName')">
              <el-input
                v-model="query.name"
                clearable
                maxlength="64"
                data-test="filter-name"
                :placeholder="t('kol.namePlaceholder')"
              />
            </el-form-item>
            <el-form-item :label="t('kol.email')">
              <el-input
                v-model="query.email"
                clearable
                maxlength="128"
                data-test="filter-email"
                :placeholder="t('kol.emailPlaceholder')"
              />
            </el-form-item>
          </template>
          <template v-if="abilities.canInviteKol" #actions>
            <el-button type="primary" data-test="invite-kol" @click="inviteVisible = true">
              + {{ t("kol.registerLink") }}
            </el-button>
          </template>
        </ListSearch>

        <DataTable
          class="kol-fit"
          table-id="kol"
          fill-available-height
          :rows="rows"
          :columns="columns"
          :loading="loading"
          :total="total"
          :page="query.page"
          :size="query.size"
          :filter-count="chips.length"
          @update:page="onPage"
          @update:size="onSize"
          @refresh="load"
          @clear-filters="reset"
        >
          <template #cell-kol="{ row }"
            ><NameCode :name="asKol(row).name" :code="asKol(row).kolCode"
              ><span data-test="kol-name">{{ asKol(row).name }}</span></NameCode
            ></template
          >
          <template #cell-contact="{ row }">{{ contactText(asKol(row)) }}</template>
          <template #cell-email="{ row }">{{ asKol(row).email }}</template>
          <template #cell-platform="{ row }">{{ platformText(asKol(row)) }}</template>
          <template #cell-rate="{ row }">{{ formatRate(asKol(row).defaultRate) }}</template>
          <template #cell-inviteTime="{ row }">
            <span data-test="invite-time">{{ formatInstantToMinute(asKol(row).inviteTime) }}</span>
          </template>
          <template #cell-inviter="{ row }">{{ asKol(row).bdUserName ?? "-" }}</template>
          <template #cell-review="{ row }">
            <button
              v-if="reviewClickable(asKol(row))"
              type="button"
              class="review-entry"
              data-test="review-entry"
              @click="openReview(asKol(row))"
            >
              <el-tag type="warning">{{ reviewStatusText(asKol(row).reviewStatus) }}</el-tag>
            </button>
            <el-tag v-else type="success" data-test="review-status">{{
              reviewStatusText(asKol(row).reviewStatus)
            }}</el-tag>
          </template>
          <template #cell-login="{ row }">
            <el-tag
              :type="
                asKol(row).loginStatus === 'NORMAL'
                  ? 'success'
                  : asKol(row).loginStatus === 'FROZEN'
                    ? 'info'
                    : 'warning'
              "
              data-test="login-status"
              >{{ loginStatusText(asKol(row).loginStatus) }}</el-tag
            >
          </template>
          <template #cell-relation="{ row }">
            <span class="relation">
              <el-tag
                data-test="relation-status"
                :type="
                  asKol(row).displayRelationStatus
                    ? relationTagType(asKol(row).displayRelationStatus)
                    : 'info'
                "
                >{{ relationStatusText(asKol(row).displayRelationStatus) }}</el-tag
              >
            </span>
          </template>
          <template #actions="{ row }">
            <!-- The review entry is in its status column; order, permissions, and disabled
                 rules of the row actions are declared in rowActions above. -->
            <RowActions :actions="rowActions(asKol(row))" />
          </template>
        </DataTable>
      </el-tab-pane>
      <el-tab-pane
        v-if="isSuperAdmin"
        :label="t('kol.manageTab')"
        name="manage"
        lazy
        data-test="kol-manage-pane"
      >
        <KolManageTab @changed="load" />
      </el-tab-pane>
    </el-tabs>

    <KolProfileDialog
      v-model="profileVisible"
      :kol="profileKol"
      :review="reviewMode"
      @saved="load"
    />
    <CommissionDialog v-model="commissionVisible" :kol="commissionKol" @saved="load" />
    <KolFundDialog v-model="fundVisible" :kol="fundKol" />
    <CleanupConfigDialog v-model="cleanupVisible" :kol="cleanupKol" @changed="load" />
    <InviteKolDialog v-model="inviteVisible" />
  </div>
</template>

<style scoped>
.kol-tabs {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.kol-tabs :deep(.el-tabs__content) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow: visible;
}
.kol-tabs :deep(.el-tab-pane) {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-4);
  flex: 1;
  min-height: 0;
}
.developing {
  color: var(--tcrm-color-text-secondary);
}
.review-entry {
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}
.review-entry:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
/* Keep headers intact and let the table's own viewport provide horizontal scrolling. The 10px
   side padding buys four more characters per column than the 12px default without widening the
   card, which is what keeps the timestamps readable at 1844px. */
.kol-fit :deep(.el-table .cell) {
  /* Header cells keep 10px padding on each side, so a column's width floor is the measured
     header text plus 20px; below that the header itself gets ellipsized, which reads worse
     than truncating values. Narrow floors must take pixels from adjacent ellipsis columns. */
  padding-inline: 10px;
}
.kol-fit :deep(th.el-table__cell .cell) {
  white-space: nowrap;
}
.kol-fit :deep(.el-tag) {
  padding-inline: 4px;
}
</style>
