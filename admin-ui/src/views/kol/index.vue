<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { inviteKol, listKols, updateKolStatus } from "@/api/kol/kol";
import { cleanupKol } from "@/api/kol/promotion";
import type { TransferTarget } from "@/api/kol/bd-transfer";
import { listBdUsers } from "@/api/system/bd-user";
import type { BdUserVO, DisplayRelationStatus, KolVO, ReviewStatus } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { useBatchAction } from "@/composables/useBatchAction";
import { useListExport } from "@/composables/useListExport";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";
import { accessOf, canExport, kolAbilities, linkAbilities } from "@/utils/permission";
import {
  DISPLAY_RELATION_STATUSES,
  relationStatusLabelKey,
  relationTagType
} from "@/utils/promotion";
import { formatRate } from "@/utils/rate";
import { readPageSize } from "@/utils/table-prefs";
import BdTransferDialog from "./BdTransferDialog.vue";
import ClaimKolDialog from "./ClaimKolDialog.vue";
import CleanupConfigDialog from "./CleanupConfigDialog.vue";
import CleanupRecordsPage from "./CleanupRecordsPage.vue";
import KolDetailDrawer from "./KolDetailDrawer.vue";
import KolFormDialog from "./KolFormDialog.vue";
import KolImportDialog from "./KolImportDialog.vue";
import KolLinksDrawer from "./KolLinksDrawer.vue";
import PromoSheetUploadDialog from "./PromoSheetUploadDialog.vue";
import PromotionConfigDrawer from "./PromotionConfigDrawer.vue";
import PromotionStatusDialog from "./PromotionStatusDialog.vue";
import RegisterLinkDialog from "./RegisterLinkDialog.vue";

defineOptions({ name: "KolListPage" });

const { t } = useI18n();
const me = useUserStore();
/** Button visibility only; the backend enforces the same rules (task 10b §4.3). */
const abilities = computed(() => kolAbilities(me.user));
// Creating creators in the console (single and Excel) is hidden for now: creators join through
// their BD's register link (owner decision 2026-10-16). The backend endpoints stay in place.
const KOL_CREATE_ENABLED = false;
const links = computed(() => linkAbilities(me.user));
const exportAllowed = computed(() => canExport(me.user));
const canViewCleanupRecords = computed(() => accessOf(me.user).can("kol:view"));
const needsBdList = computed(
  () => abilities.value.canFilterBd || abilities.value.canAssignBd || abilities.value.canChangeBd
);

const loading = ref(false);
const rows = ref<KolVO[]>([]);
const total = ref(0);
const bdUsers = ref<BdUserVO[]>([]);
const query = reactive<{
  page: number;
  size: number;
  keyword: string;
  reviewStatus?: ReviewStatus;
  relationStatus?: DisplayRelationStatus;
  loginStatus?: "FROZEN" | "NORMAL_OR_ABNORMAL";
  bdUserId?: string;
}>({ page: 1, size: readPageSize("kol", 20), keyword: "" });

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

const columns = computed<DataColumn[]>(() => [
  { key: "kolCode", label: t("kol.code"), width: 160, mono: true },
  { key: "name", label: t("common.name"), prop: "name", minWidth: 120 },
  { key: "contact", label: t("kol.phoneWhatsapp"), minWidth: 170 },
  { key: "email", label: t("common.email"), minWidth: 200, mono: true },
  { key: "platform", label: t("kol.primaryPlatform"), minWidth: 140 },
  { key: "rate", label: t("kol.defaultRate"), width: 130 },
  { key: "inviteTime", label: t("kol.inviteTime"), width: 170, mono: true },
  { key: "bd", label: t("kol.bd"), minWidth: 110 },
  { key: "review", label: t("kol.reviewStatus"), width: 100 },
  { key: "login", label: t("kol.loginStatus"), width: 100 },
  { key: "relation", label: t("kol.relationStatus"), width: 110 },
  { key: "actions", label: t("common.actions"), width: 500 }
]);

/** Chips and empty-state filter-count reflect the last applied search, not in-progress typing. */
const applied = ref({
  keyword: "",
  reviewStatus: undefined as ReviewStatus | undefined,
  relationStatus: undefined as DisplayRelationStatus | undefined,
  loginStatus: undefined as ("FROZEN" | "NORMAL_OR_ABNORMAL") | undefined,
  bdUserId: undefined as string | undefined
});

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  const keyword = applied.value.keyword;
  if (keyword) list.push({ key: "keyword", label: t("common.keyword"), text: keyword });
  if (applied.value.reviewStatus)
    list.push({
      key: "reviewStatus",
      label: t("kol.reviewStatus"),
      text: t(`kol.reviewStatusOption.${applied.value.reviewStatus}`)
    });
  if (applied.value.relationStatus)
    list.push({
      key: "relationStatus",
      label: t("kol.relationStatus"),
      text: t(relationStatusLabelKey(applied.value.relationStatus))
    });
  if (applied.value.loginStatus) {
    list.push({
      key: "loginStatus",
      label: t("kol.loginStatus"),
      text: t(`kol.loginStatusOption.${applied.value.loginStatus}`)
    });
  }
  if (applied.value.bdUserId) {
    const bd = bdUsers.value.find(item => item.id === applied.value.bdUserId);
    list.push({
      key: "bd",
      label: t("kol.bd"),
      text: bd
        ? t("kol.bdOption", { nickname: bd.nickname, username: bd.username })
        : applied.value.bdUserId
    });
  }
  return list;
});

const formVisible = ref(false);
const editingKol = ref<KolVO | null>(null);
/** True while the edit dialog is the review screen reached from the status column (25b §3.3). */
const reviewMode = ref(false);
const transferVisible = ref(false);
const transferTarget = ref<TransferTarget | null>(null);
const detailVisible = ref(false);
const detailId = ref<string | null>(null);
const importVisible = ref(false);
const linksVisible = ref(false);
const linksKol = ref<KolVO | null>(null);
const registerVisible = ref(false);
const invitingId = ref<string | null>(null);
const promoSheetVisible = ref(false);
const promoSheetKol = ref<KolVO | null>(null);
const configVisible = ref(false);
const configReadOnly = ref(false);
const configKol = ref<KolVO | null>(null);
const statusDialogVisible = ref(false);
const statusDialogKol = ref<KolVO | null>(null);
const claimVisible = ref(false);
const cleanupVisible = ref(false);
const activeTab = ref("creators");

function showInvite(row: KolVO): boolean {
  return abilities.value.canWrite && row.accountStatus === 0 && row.registerSource === 1;
}

async function load() {
  loading.value = true;
  try {
    const page = await listKols({
      page: query.page,
      size: query.size,
      ...appliedQuery()
    });
    rows.value = page.items;
    total.value = Number(page.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function appliedQuery() {
  return {
    keyword: applied.value.keyword || undefined,
    reviewStatus: applied.value.reviewStatus,
    relationStatus: applied.value.relationStatus,
    loginStatus: applied.value.loginStatus,
    bdUserId: abilities.value.canFilterBd ? applied.value.bdUserId || undefined : undefined
  };
}

const { exporting, exportCsv } = useListExport<KolVO>({
  fileBase: () => t("export.fileKol"),
  fetchPage: (page, size) => listKols({ page, size, ...appliedQuery() }),
  columns: () => [
    { label: t("kol.code"), value: row => row.kolCode },
    { label: t("common.name"), value: row => row.name },
    { label: t("common.email"), value: row => row.email },
    { label: t("kol.phone"), value: row => row.phone ?? "" },
    { label: t("kol.whatsapp"), value: row => row.whatsapp ?? "" },
    {
      label: t("kol.primaryPlatform"),
      value: row => row.socialAccounts.find(item => item.primary)?.platform ?? ""
    },
    {
      label: t("kol.primaryHandle"),
      value: row => row.socialAccounts.find(item => item.primary)?.handle ?? ""
    },
    {
      label: t("kol.defaultRate"),
      value: row =>
        `${formatRate(row.defaultRate)} ${row.configVersion ? `v${row.configVersion}` : ""}`
    },
    { label: t("kol.inviteTime"), value: row => formatInstant(row.inviteTime) },
    { label: t("kol.reviewStatus"), value: row => t(`kol.reviewStatusOption.${row.reviewStatus}`) },
    { label: t("kol.loginStatus"), value: row => t(`kol.loginStatusOption.${row.loginStatus}`) },
    {
      label: t("kol.relationStatus"),
      value: row => t(relationStatusLabelKey(row.displayRelationStatus))
    }
  ]
});

const selection = ref<KolVO[]>([]);
const batch = useBatchAction();

function batchStatus(target: 0 | 1): void {
  const targets = selection.value.filter(row => row.status !== target);
  if (targets.length === 0) {
    ElMessage.info(t("batch.nothingToChange"));
    return;
  }
  void batch.run({
    rows: targets,
    title: target === 0 ? t("kol.disableTitle") : t("kol.enableTitle"),
    message:
      target === 0
        ? t("batch.confirmDisable", { n: targets.length })
        : t("batch.confirmEnable", { n: targets.length }),
    label: row => row.kolCode,
    action: row => updateKolStatus(row.id, target),
    after: load
  });
}

async function loadBdUsers() {
  if (!needsBdList.value) return;
  try {
    bdUsers.value = await listBdUsers();
  } catch (error) {
    notifyError(error);
  }
}

function snapshotFilters(): void {
  applied.value = {
    keyword: query.keyword.trim(),
    reviewStatus: query.reviewStatus,
    relationStatus: query.relationStatus,
    loginStatus: query.loginStatus,
    bdUserId: abilities.value.canFilterBd ? query.bdUserId : undefined
  };
}

function search() {
  query.page = 1;
  snapshotFilters();
  void load();
}

function reset() {
  query.keyword = "";
  query.reviewStatus = undefined;
  query.relationStatus = undefined;
  query.loginStatus = undefined;
  query.bdUserId = undefined;
  search();
}

function removeChip(key: string): void {
  if (key === "keyword") query.keyword = "";
  if (key === "reviewStatus") query.reviewStatus = undefined;
  if (key === "relationStatus") query.relationStatus = undefined;
  if (key === "loginStatus") query.loginStatus = undefined;
  if (key === "bd") query.bdUserId = undefined;
  search();
}

function onPage(page: number): void {
  query.page = page;
  void load();
}

function onSize(size: number): void {
  query.size = size;
}

function openCreate() {
  editingKol.value = null;
  reviewMode.value = false;
  formVisible.value = true;
}

function openEdit(row: KolVO) {
  editingKol.value = row;
  reviewMode.value = false;
  formVisible.value = true;
}

function openReview(row: KolVO) {
  editingKol.value = row;
  reviewMode.value = true;
  formVisible.value = true;
}

function openConfig(
  row: KolVO,
  readOnly = !abilities.value.canWrite || row.reviewStatus !== "APPROVED"
) {
  configKol.value = row;
  configReadOnly.value = readOnly;
  configVisible.value = true;
}

function openStatusDialog(row: KolVO) {
  statusDialogKol.value = row;
  statusDialogVisible.value = true;
}

/**
 * Clicking the status tag opens the screen that status asks for (25b §3.2): review, promotion
 * settings, the promotion data in the detail drawer, or a BD transfer for "needs update".
 */
function handleReviewStatusClick(row: KolVO): void {
  if (row.reviewStatus !== "PENDING") return;
  if (abilities.value.canWrite) openReview(row);
  else ElMessage.info(t("kol.viewOnlyStatusHint"));
}

function handleRelationStatusClick(row: KolVO): void {
  const status = row.displayRelationStatus;
  if (status === "PENDING_PROMOTION") {
    // The status label is the shortcut to promotion configuration. Unreviewed
    // creators open it read-only; the backend still requires review to save.
    openConfig(row, !abilities.value.canWrite || row.reviewStatus !== "APPROVED");
    return;
  }
  if (status === "PROMOTING" || status === "INVALID") {
    openConfig(row, true);
    return;
  }
  // PENDING_UPDATE: only the super administrator can reassign the BD.
  if (abilities.value.canChangeBd) {
    transferTarget.value = { kolIds: [row.id] };
    transferVisible.value = true;
  } else {
    ElMessage.info(t("kol.pendingUpdateHint"));
  }
}

/** Batch toolbar entry: the selection never spans pages, so it stays far below the 500 cap. */
function openTransferSelected() {
  if (selection.value.length === 0) return;
  transferTarget.value = { kolIds: selection.value.map(row => row.id) };
  transferVisible.value = true;
}

function openBd(row: KolVO) {
  transferTarget.value = { kolIds: [row.id] };
  transferVisible.value = true;
}

function openDetail(row: KolVO) {
  detailId.value = row.id;
  detailVisible.value = true;
}

function openLinks(row: KolVO) {
  linksKol.value = row;
  linksVisible.value = true;
}

function openPromoSheet(row: KolVO) {
  promoSheetKol.value = row;
  promoSheetVisible.value = true;
}

async function sendInvite(row: KolVO) {
  invitingId.value = row.id;
  try {
    await inviteKol(row.id);
    ElMessage.success(t("kol.inviteSent"));
    await load();
  } catch (error) {
    notifyError(error);
    if (error instanceof ApiError && error.errorCode === "KOL_ALREADY_ACTIVATED") await load();
  } finally {
    invitingId.value = null;
  }
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

async function manualCleanup(row: KolVO) {
  try {
    const result = await ElMessageBox.prompt(
      t("cleanup.manualConfirm", { code: row.kolCode }),
      t("cleanup.manualTitle"),
      {
        type: "warning",
        inputType: "textarea",
        inputPlaceholder: t("cleanup.reasonPlaceholder"),
        inputValidator: value => Boolean(value?.trim()) && [...(value ?? "")].length <= 200,
        inputErrorMessage: t("cleanup.reasonRequired")
      }
    );
    await cleanupKol(row.id, result.value.trim());
    ElMessage.success(t("cleanup.manualStarted"));
    await load();
  } catch (error) {
    if (error !== "cancel" && error !== "close") notifyError(error);
  }
}

onMounted(() => {
  void load();
  void loadBdUsers();
});
</script>

<template>
  <div class="list-page">
    <el-tabs v-model="activeTab" class="kol-tabs">
      <el-tab-pane :label="t('route.kols')" name="creators">
        <ListSearch :chips="chips" has-more @search="search" @reset="reset" @remove="removeChip">
          <template #primary>
            <el-form-item :label="t('common.keyword')">
              <el-input
                v-model="query.keyword"
                clearable
                maxlength="128"
                :placeholder="t('kol.keywordPlaceholder')"
              />
            </el-form-item>
          </template>
          <template #more>
            <el-form-item :label="t('kol.reviewStatus')" data-test="review-filter">
              <el-select
                v-model="query.reviewStatus"
                clearable
                :placeholder="t('common.all')"
                style="width: 130px"
              >
                <el-option :label="t('kol.reviewStatusOption.PENDING')" value="PENDING" />
                <el-option :label="t('kol.reviewStatusOption.APPROVED')" value="APPROVED" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('kol.relationStatus')" data-test="relation-filter">
              <el-select
                v-model="query.relationStatus"
                clearable
                :placeholder="t('common.all')"
                style="width: 150px"
              >
                <el-option
                  v-for="status in DISPLAY_RELATION_STATUSES"
                  :key="status"
                  :label="t(relationStatusLabelKey(status))"
                  :value="status"
                />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('kol.loginStatus')" data-test="login-filter">
              <el-select
                v-model="query.loginStatus"
                clearable
                :placeholder="t('common.all')"
                style="width: 150px"
              >
                <el-option :label="t('kol.loginStatusOption.FROZEN')" value="FROZEN" />
                <el-option
                  :label="t('kol.loginStatusOption.NORMAL_OR_ABNORMAL')"
                  value="NORMAL_OR_ABNORMAL"
                />
              </el-select>
            </el-form-item>
            <el-form-item v-if="abilities.canFilterBd" :label="t('kol.bd')" data-test="bd-filter">
              <el-select
                v-model="query.bdUserId"
                clearable
                filterable
                :placeholder="t('common.all')"
                style="width: 160px"
              >
                <el-option
                  v-for="bd in bdUsers"
                  :key="bd.id"
                  :label="t('kol.bdOption', { nickname: bd.nickname, username: bd.username })"
                  :value="bd.id"
                />
              </el-select>
            </el-form-item>
          </template>
          <template
            v-if="
              abilities.canWrite ||
              abilities.canRegisterLink ||
              abilities.canChangeBd ||
              abilities.canClaimKol ||
              abilities.canManageCleanup
            "
            #actions
          >
            <el-button
              v-if="abilities.canClaimKol"
              data-test="claim-kol"
              @click="claimVisible = true"
            >
              {{ t("kol.claim") }}
            </el-button>
            <el-button
              v-if="abilities.canManageCleanup"
              data-test="cleanup-config"
              @click="cleanupVisible = true"
            >
              {{ t("cleanup.title") }}
            </el-button>
            <el-button
              v-if="abilities.canRegisterLink"
              data-test="register-link"
              @click="registerVisible = true"
            >
              {{ t("kol.registerLink") }}
            </el-button>
            <el-button
              v-if="abilities.canChangeBd"
              type="warning"
              data-test="transfer-bd"
              :disabled="selection.length === 0"
              @click="openTransferSelected"
            >
              {{ t("bdTransfer.action") }}
            </el-button>
            <el-button
              v-if="KOL_CREATE_ENABLED && abilities.canWrite"
              type="success"
              data-test="create"
              @click="openCreate"
            >
              {{ t("kol.create") }}
            </el-button>
            <el-button
              v-if="KOL_CREATE_ENABLED && abilities.canWrite"
              data-test="import"
              @click="importVisible = true"
            >
              {{ t("kol.import") }}
            </el-button>
          </template>
        </ListSearch>

        <DataTable
          table-id="kol"
          :rows="rows"
          :columns="columns"
          :loading="loading"
          :total="total"
          :page="query.page"
          :size="query.size"
          :filter-count="chips.length"
          :exportable="exportAllowed"
          :exporting="exporting"
          :selectable="abilities.canWrite"
          @export="exportCsv"
          @selection-change="selection = $event as KolVO[]"
          @update:page="onPage"
          @update:size="onSize"
          @refresh="load"
          @clear-filters="reset"
        >
          <template #batch>
            <el-button
              type="success"
              plain
              size="small"
              :loading="batch.running.value"
              data-test="batch-enable"
              @click="batchStatus(1)"
              >{{ t("batch.enable") }}</el-button
            >
            <el-button
              type="danger"
              plain
              size="small"
              :loading="batch.running.value"
              data-test="batch-disable"
              @click="batchStatus(0)"
              >{{ t("batch.disable") }}</el-button
            >
          </template>
          <template #cell-kolCode="{ row }"><CopyText :value="asKol(row).kolCode" /></template>
          <template #cell-email="{ row }"><CopyText :value="asKol(row).email" /></template>
          <template #cell-contact="{ row }">{{
            [asKol(row).phone, asKol(row).whatsapp].filter(Boolean).join(" / ") || "-"
          }}</template>
          <template #cell-platform="{ row }">
            {{ asKol(row).socialAccounts.find(item => item.primary)?.platform || "-" }} /
            {{ asKol(row).socialAccounts.find(item => item.primary)?.handle || "-" }}
          </template>
          <template #cell-bd="{ row }">{{
            asKol(row).bdUserName ?? t("common.unassigned")
          }}</template>
          <template #cell-rate="{ row }"
            >{{ formatRate(asKol(row).defaultRate) }}
            <a v-if="asKol(row).configVersion" href="#" @click.prevent="openConfig(asKol(row))"
              >v{{ asKol(row).configVersion }}</a
            ></template
          >
          <template #cell-inviteTime="{ row }">{{ formatInstant(asKol(row).inviteTime) }}</template>
          <template #cell-review="{ row }">
            <span
              class="status-interaction"
              :role="
                abilities.canWrite && asKol(row).reviewStatus === 'PENDING' ? 'button' : undefined
              "
              :tabindex="abilities.canWrite && asKol(row).reviewStatus === 'PENDING' ? 0 : -1"
              @click="handleReviewStatusClick(asKol(row))"
              @keydown.enter.prevent="handleReviewStatusClick(asKol(row))"
              @keydown.space.prevent="handleReviewStatusClick(asKol(row))"
            >
              <el-tag
                :type="asKol(row).reviewStatus === 'APPROVED' ? 'success' : 'warning'"
                class="status-tag"
                data-test="review-status"
                >{{ reviewStatusText(asKol(row).reviewStatus) }}</el-tag
              >
            </span>
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
              class="status-interaction"
              :role="
                abilities.canWrite ||
                ['PROMOTING', 'INVALID'].includes(asKol(row).displayRelationStatus)
                  ? 'button'
                  : undefined
              "
              :tabindex="
                abilities.canWrite ||
                ['PROMOTING', 'INVALID'].includes(asKol(row).displayRelationStatus)
                  ? 0
                  : -1
              "
              @click="handleRelationStatusClick(asKol(row))"
              @keydown.enter.prevent="handleRelationStatusClick(asKol(row))"
              @keydown.space.prevent="handleRelationStatusClick(asKol(row))"
            >
              <el-tag
                class="status-tag"
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
            <el-button link type="primary" @click="openDetail(asKol(row))">{{
              t("common.detail")
            }}</el-button>
            <el-button
              v-if="links.canViewLinks"
              link
              type="primary"
              data-test="links"
              @click="openLinks(asKol(row))"
            >
              {{ t("kol.links") }}
            </el-button>
            <el-button
              v-if="abilities.canWrite"
              link
              type="primary"
              data-test="promo-sheet"
              @click="openPromoSheet(asKol(row))"
            >
              {{ t("promoSheet.open") }}
            </el-button>
            <el-button
              v-if="showInvite(asKol(row))"
              link
              type="primary"
              data-test="invite"
              :loading="invitingId === asKol(row).id"
              @click="sendInvite(asKol(row))"
            >
              {{ t("kol.invite") }}
            </el-button>
            <template v-if="abilities.canWrite">
              <el-button link type="primary" data-test="edit" @click="openEdit(asKol(row))">
                {{ t("common.edit") }}
              </el-button>
              <el-button
                v-if="asKol(row).reviewStatus === 'PENDING'"
                link
                type="primary"
                data-test="review"
                @click="openReview(asKol(row))"
                >{{ t("kol.review") }}</el-button
              >
              <el-button
                v-if="asKol(row).reviewStatus === 'APPROVED'"
                link
                type="primary"
                data-test="promotion-config"
                @click="openConfig(asKol(row))"
                >{{ t("promotion.configTitle") }}</el-button
              >
              <el-button
                link
                type="primary"
                data-test="change-status"
                @click="openStatusDialog(asKol(row))"
                >{{ t("kol.changeRelationStatus") }}</el-button
              >
              <el-button
                link
                :type="asKol(row).status === 1 ? 'danger' : 'success'"
                data-test="status"
                @click="toggleStatus(asKol(row))"
              >
                {{ asKol(row).status === 1 ? t("kol.freeze") : t("kol.unfreeze") }}
              </el-button>
              <el-button
                link
                type="danger"
                data-test="manual-cleanup"
                @click="manualCleanup(asKol(row))"
                >{{ t("cleanup.manual") }}</el-button
              >
            </template>
            <el-button
              v-if="abilities.canChangeBd && asKol(row).id"
              link
              type="warning"
              data-test="change-bd"
              @click="openBd(asKol(row))"
            >
              {{ t("kol.changeBd") }}
            </el-button>
          </template>
        </DataTable>
      </el-tab-pane>
      <el-tab-pane
        v-if="canViewCleanupRecords"
        :label="t('cleanup.records')"
        name="cleanup"
        lazy
        data-test="cleanup-records-tab"
      >
        <CleanupRecordsPage :can-write="abilities.canWrite" />
      </el-tab-pane>
    </el-tabs>

    <KolFormDialog
      v-model="formVisible"
      :kol="editingKol"
      :review="reviewMode"
      :can-pick-bd="abilities.canAssignBd"
      :bd-users="bdUsers"
      @saved="load"
    />
    <BdTransferDialog v-model="transferVisible" :target="transferTarget" @saved="load" />
    <KolDetailDrawer v-model="detailVisible" :kol-id="detailId" @open-config="openConfig" />
    <KolImportDialog v-model="importVisible" @finished="search" />
    <KolLinksDrawer
      v-model="linksVisible"
      :kol-code="linksKol?.kolCode ?? null"
      :kol-name="linksKol?.name"
    />
    <RegisterLinkDialog v-model="registerVisible" />
    <PromoSheetUploadDialog
      v-model="promoSheetVisible"
      :kol-id="promoSheetKol?.id ?? null"
      :kol-label="promoSheetKol ? `${promoSheetKol.name} (${promoSheetKol.kolCode})` : ''"
    />
    <PromotionConfigDrawer
      v-model="configVisible"
      :kol="configKol"
      :read-only="configReadOnly"
      @saved="load"
      @open-review="configKol && openReview(configKol)"
    />
    <PromotionStatusDialog
      v-model="statusDialogVisible"
      :kol="statusDialogKol"
      @changed="load"
      @open-config="statusDialogKol && openConfig(statusDialogKol)"
      @open-review="statusDialogKol && openReview(statusDialogKol)"
    />
    <ClaimKolDialog v-model="claimVisible" @claimed="load" />
    <CleanupConfigDialog v-model="cleanupVisible" />
  </div>
</template>

<style scoped>
.status-tag {
  cursor: pointer;
}

.status-interaction {
  display: inline-flex;
  cursor: pointer;
}

.status-interaction:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
</style>
