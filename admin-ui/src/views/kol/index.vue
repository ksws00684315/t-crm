<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { updateKolStatus } from "@/api/kol/kol";
import type { DisplayRelationStatus, KolVO, ReviewStatus } from "@/api/types";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
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

defineOptions({ name: "KolListPage" });

/**
 * The creator list after the owner's 2026-10-20 review (task 32 §2.2): the layout follows the original
 * system, the columns and the six fixed row actions follow 1018 rows 7–15. The super
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

/** The roster preserves useful scan widths; the table scrolls inside its card when needed. */
const columns = computed<DataColumn[]>(() => [
  { key: "kolCode", label: t("kol.kolId"), width: 148, tooltip: true },
  { key: "name", label: t("kol.kolName"), prop: "name", width: 160, tooltip: true },
  { key: "contact", label: t("kol.phoneWhatsapp"), width: 164, tooltip: true },
  { key: "email", label: t("kol.email"), width: 240, tooltip: true },
  { key: "platform", label: t("kol.primaryPlatform"), width: 200, tooltip: true },
  { key: "rate", label: t("kol.defaultRate"), width: 120 },
  { key: "prepay", label: t("kol.prepay"), width: 120 },
  { key: "inviteTime", label: t("kol.inviteTime"), width: 184, tooltip: true },
  { key: "inviter", label: t("kol.inviter"), width: 144, tooltip: true },
  { key: "review", label: t("kol.reviewStatus"), width: 120 },
  { key: "login", label: t("kol.loginStatus"), width: 120 },
  { key: "relation", label: t("kol.relationStatus"), width: 136 },
  { key: "actions", label: t("common.actions"), width: 320 }
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

/** Only "pending promotion" opens the commission dialog from the status tag (04.03). */
function relationClickable(row: KolVO): boolean {
  return (
    row.displayRelationStatus === "PENDING_PROMOTION" &&
    abilities.value.canWrite &&
    row.reviewStatus === "APPROVED" &&
    rowOwned(row)
  );
}

function handleRelationClick(row: KolVO): void {
  if (relationClickable(row)) openCommission(row);
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
          <template #cell-kolCode="{ row }">{{ asKol(row).kolCode }}</template>
          <template #cell-name="{ row }"
            ><span data-test="kol-name">{{ asKol(row).name }}</span></template
          >
          <template #cell-contact="{ row }">{{ contactText(asKol(row)) }}</template>
          <template #cell-email="{ row }">{{ asKol(row).email }}</template>
          <template #cell-platform="{ row }">{{ platformText(asKol(row)) }}</template>
          <template #cell-rate="{ row }">{{ formatRate(asKol(row).defaultRate) }}</template>
          <template #cell-prepay>
            <span class="developing">{{ t("common.developing") }}</span>
          </template>
          <template #cell-inviteTime="{ row }">
            <span data-test="invite-time">{{ formatInstant(asKol(row).inviteTime) }}</span>
          </template>
          <template #cell-inviter="{ row }">{{ asKol(row).bdUserName ?? "-" }}</template>
          <template #cell-review="{ row }">
            <el-tag
              :type="asKol(row).reviewStatus === 'APPROVED' ? 'success' : 'warning'"
              data-test="review-status"
              >{{ reviewStatusText(asKol(row).reviewStatus) }}</el-tag
            >
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
            <span
              class="relation"
              :class="{ 'relation--clickable': relationClickable(asKol(row)) }"
              :role="relationClickable(asKol(row)) ? 'button' : undefined"
              :tabindex="relationClickable(asKol(row)) ? 0 : -1"
              :title="relationClickable(asKol(row)) ? t('promotion.configTitle') : undefined"
              @click="handleRelationClick(asKol(row))"
              @keydown.enter.prevent="handleRelationClick(asKol(row))"
            >
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
            <!-- 04.01: six fixed actions, this order, disabled when not applicable; wrapped
                 as two rows of three so the column stays ~200px (2026-10-20 review). -->
            <div class="actions-cell" data-test="row-actions">
              <el-button
                link
                type="primary"
                data-test="action-edit"
                :disabled="!abilities.canWrite"
                @click="openEdit(asKol(row))"
                >{{ t("kol.actionEdit") }}</el-button
              >
              <el-button
                link
                type="primary"
                data-test="action-review"
                :disabled="!(abilities.canWrite && asKol(row).reviewStatus === 'PENDING')"
                @click="openReview(asKol(row))"
                >{{ t("kol.actionReview") }}</el-button
              >
              <el-button
                link
                type="primary"
                data-test="action-commission"
                :disabled="
                  !(
                    abilities.canWrite &&
                    asKol(row).reviewStatus === 'APPROVED' &&
                    rowOwned(asKol(row))
                  )
                "
                @click="openCommission(asKol(row))"
                >{{ t("kol.actionCommission") }}</el-button
              >
              <el-tooltip :content="t('common.developing')" placement="top">
                <span class="action-disabled">
                  <el-button link type="primary" data-test="action-prepay" disabled>{{
                    t("kol.actionPrepay")
                  }}</el-button>
                </span>
              </el-tooltip>
              <el-button
                link
                type="primary"
                data-test="action-cleanup"
                :disabled="!(abilities.canWrite && rowOwned(asKol(row)))"
                @click="openCleanup(asKol(row))"
                >{{ t("kol.actionCleanup") }}</el-button
              >
              <el-button
                link
                :type="asKol(row).status === 1 ? 'danger' : 'success'"
                data-test="action-freeze"
                :disabled="!(abilities.canWrite && rowOwned(asKol(row)))"
                @click="toggleStatus(asKol(row))"
                >{{ asKol(row).status === 1 ? t("kol.freeze") : t("kol.unfreeze") }}</el-button
              >
            </div>
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
  flex: 1;
  min-height: 0;
}
.developing {
  color: var(--tcrm-color-text-secondary);
}
.relation--clickable {
  cursor: pointer;
}
.relation--clickable:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
/* Keep headers intact and let the table's own viewport provide horizontal scrolling. */
.kol-fit :deep(.el-table .cell) {
  padding-inline: 12px;
}
.kol-fit :deep(th.el-table__cell .cell) {
  white-space: nowrap;
}
.actions-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  align-items: center;
}
.actions-cell .el-button {
  margin: 0;
  padding: 0;
  height: auto;
}
.action-disabled {
  display: inline-flex;
}
</style>
