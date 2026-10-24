<script setup lang="ts">
import { Refresh, WarningFilled } from "@element-plus/icons-vue";
import {
  ElButton,
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules,
  type MessageHandler
} from "element-plus";
import { computed, h, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  checkShopApis,
  clearSourceShop,
  createShop,
  editShop,
  exitCopyPool,
  joinCopyPool,
  pageShops,
  refreshShopCapacity,
  setSourceShop,
  updateShopStatus
} from "@/api/product/shop";
import type { CopyIneligibleReason, ShopVO } from "@/api/types";
import {
  getWeidianApp,
  saveWeidianApp,
  startWeidianAuthorization,
  type WeidianAppVO
} from "@/api/integration/weidian";
import { triggerOrderSync } from "@/api/order/order";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import RowActions, { type RowAction } from "@/components/RowActions.vue";
import { useLocaleStore } from "@/stores/locale";
import { useUserStore } from "@/stores/user";
import { formatInstantToMinute } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { ApiError } from "@/utils/http/error";
import { accessOf } from "@/utils/permission";
import { readPageSize } from "@/utils/table-prefs";

defineOptions({ name: "ShopListPage" });

const { t } = useI18n();
const userStore = useUserStore();
const access = computed(() => accessOf(userStore.user));
const canManage = computed(() => access.value.isSuperAdmin);
const zhWidth = computed(() => useLocaleStore().locale === "zh-CN");
const px = (zh: number, en: number): number => (zhWidth.value ? zh : en);
const canSyncOrders = computed(() => access.value.can("order:manage"));
const loading = ref(false);
const rows = ref<ShopVO[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(readPageSize("shops", 20));
const query = reactive<{ shopCode: string; shopName: string; channel: "" | "WEIDIAN" }>({
  shopCode: "",
  shopName: "",
  channel: ""
});
const applied = ref<{ shopCode: string; shopName: string; channel: "" | "WEIDIAN" }>({
  shopCode: "",
  shopName: "",
  channel: ""
});
const formVisible = ref(false);
const editing = ref<ShopVO | null>(null);
const formRef = ref<FormInstance>();
const form = reactive({
  shopCode: "",
  shopName: "",
  channel: "WEIDIAN" as const,
  maxItems: 20000,
  weidianShopId: "",
  remark: ""
});
const saving = ref(false);
const refreshingId = ref<string | null>(null);
const appDialogVisible = ref(false);
const appLoading = ref(false);
const appSaving = ref(false);
const appStatus = ref<WeidianAppVO | null>(null);
const appFormRef = ref<FormInstance>();
const appForm = reactive({ appKey: "", appSecret: "" });

const rules = computed<FormRules>(() => ({
  shopName: [{ required: true, message: t("shop.nameRequired"), trigger: "blur" }],
  channel: [{ required: true, message: t("shop.channelRequired"), trigger: "change" }],
  maxItems: [
    { required: true, message: t("shop.maxItemsRequired"), trigger: "change" },
    {
      type: "number",
      min: 1,
      max: 1000000,
      message: t("shop.maxItemsRange"),
      trigger: "change"
    }
  ]
}));

const appRules = computed<FormRules>(() => ({
  appKey: [
    { required: true, message: t("shop.weidianApp.appKeyRequired"), trigger: "blur" },
    { max: 64, message: t("shop.weidianApp.appKeyTooLong"), trigger: "blur" }
  ],
  appSecret: [{ required: true, message: t("shop.weidianApp.appSecretRequired"), trigger: "blur" }]
}));

const callbackUrlInvalid = computed(() => {
  const callbackUrl = appStatus.value?.callbackUrl ?? "";
  if (!callbackUrl.startsWith("https://")) return true;
  try {
    return new URL(callbackUrl).hostname.toLowerCase().endsWith(".invalid");
  } catch {
    return true;
  }
});

const columns = computed<DataColumn[]>(() => {
  const list: DataColumn[] = [
    { key: "shopCode", label: t("shop.code"), minWidth: 130 },
    { key: "shopName", label: t("shop.name"), minWidth: 140, tooltip: true },
    { key: "channel", label: t("shop.channel"), width: 90 },
    { key: "source", label: t("shop.source"), width: 100 },
    { key: "copyPool", label: t("shop.copyPool"), minWidth: 160 },
    { key: "weidianShopId", label: t("shop.weidianId"), minWidth: 140 },
    { key: "authStatus", label: t("shop.authStatus"), width: 110 },
    { key: "authExpiresAt", label: t("shop.authExpiresAt"), minWidth: 160 },
    { key: "apiStatus", label: t("shop.apiStatus"), minWidth: 250 },
    { key: "capacity", label: t("shop.capacity"), minWidth: 140 },
    { key: "status", label: t("common.status"), width: 90 }
  ];
  if (canManage.value || canSyncOrders.value) {
    list.push({ key: "actions", label: t("common.actions"), width: px(280, 380) });
  }
  return list;
});

const chips = computed<SearchChip[]>(() => {
  const result: SearchChip[] = [];
  if (applied.value.shopCode) {
    result.push({ key: "shopCode", label: t("shop.code"), text: applied.value.shopCode });
  }
  if (applied.value.shopName) {
    result.push({ key: "shopName", label: t("shop.name"), text: applied.value.shopName });
  }
  if (applied.value.channel) {
    result.push({ key: "channel", label: t("shop.channel"), text: t("shop.channelWeidian") });
  }
  return result;
});

function asShop(row: object): ShopVO {
  return row as ShopVO;
}

async function load() {
  loading.value = true;
  try {
    const result = await pageShops({
      shopCode: applied.value.shopCode || undefined,
      shopName: applied.value.shopName || undefined,
      channel: applied.value.channel || undefined,
      page: page.value,
      size: size.value
    });
    rows.value = result.items;
    total.value = Number(result.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function search() {
  applied.value = {
    shopCode: query.shopCode.trim(),
    shopName: query.shopName.trim(),
    channel: query.channel
  };
  page.value = 1;
  void load();
}

function reset() {
  query.shopCode = "";
  query.shopName = "";
  query.channel = "";
  search();
}

function removeChip(key: string) {
  if (key === "shopCode") query.shopCode = "";
  if (key === "shopName") query.shopName = "";
  if (key === "channel") query.channel = "";
  search();
}

function openCreate() {
  editing.value = null;
  form.shopCode = "";
  form.shopName = "";
  form.channel = "WEIDIAN";
  form.maxItems = 20000;
  form.weidianShopId = "";
  form.remark = "";
  formRef.value?.clearValidate();
  formVisible.value = true;
}

function openEdit(row: ShopVO) {
  editing.value = row;
  form.shopCode = row.shopCode;
  form.shopName = row.shopName;
  form.channel = row.channel;
  form.maxItems = row.maxItems;
  form.weidianShopId = row.weidianShopId ?? "";
  form.remark = row.remark ?? "";
  formRef.value?.clearValidate();
  formVisible.value = true;
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    const request = {
      shopName: form.shopName.trim(),
      channel: form.channel,
      maxItems: form.maxItems,
      weidianShopId: form.weidianShopId.trim() || null,
      remark: form.remark.trim() || null
    };
    if (editing.value) {
      await editShop(editing.value.id, request);
      ElMessage.success(t("shop.saved"));
    } else {
      await createShop(request);
      ElMessage.success(t("shop.created"));
    }
    formVisible.value = false;
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}

/** The "app not configured" notice (L-005) stays until its config dialog opens or a save succeeds. */
let appConfigNotice: MessageHandler | null = null;

function closeAppConfigNotice(): void {
  appConfigNotice?.close();
  appConfigNotice = null;
}

async function authorize(row: ShopVO) {
  const authWindow = window.open("about:blank", "_blank");
  if (authWindow) authWindow.opener = null;
  try {
    const result = await startWeidianAuthorization(row.id);
    if (authWindow) authWindow.location.href = result.authorizeUrl;
    else window.location.assign(result.authorizeUrl);
  } catch (error) {
    authWindow?.close();
    if (
      canManage.value &&
      error instanceof ApiError &&
      error.errorCode === "WEIDIAN_APP_NOT_CONFIGURED"
    ) {
      appConfigNotice = ElMessage({
        type: "error",
        duration: 0,
        showClose: true,
        message: h("span", [
          `${t("errors.WEIDIAN_APP_NOT_CONFIGURED")} `,
          h(
            ElButton,
            {
              type: "primary",
              link: true,
              "data-test": "weidian-go-config",
              onClick: () => void openAppConfig()
            },
            () => t("shop.weidianApp.goConfigure")
          )
        ])
      });
    } else {
      notifyError(error);
    }
  }
}

async function openAppConfig() {
  closeAppConfigNotice();
  appForm.appKey = "";
  appForm.appSecret = "";
  appFormRef.value?.clearValidate();
  appDialogVisible.value = true;
  appLoading.value = true;
  try {
    appStatus.value = await getWeidianApp();
    appForm.appKey = appStatus.value.appKey ?? "";
  } catch (error) {
    notifyError(error);
  } finally {
    appLoading.value = false;
  }
}

async function saveAppConfig() {
  if (!appFormRef.value || !(await appFormRef.value.validate().catch(() => false))) return;
  appSaving.value = true;
  try {
    await saveWeidianApp({ appKey: appForm.appKey.trim(), appSecret: appForm.appSecret });
    appForm.appSecret = "";
    closeAppConfigNotice();
    ElMessage.success(t("shop.weidianApp.saved"));
    appDialogVisible.value = false;
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    appSaving.value = false;
  }
}

async function sync(row: ShopVO, type: "ORDER" | "REFUND") {
  try {
    await triggerOrderSync(row.id, type);
    ElMessage.success(t("shop.syncStarted"));
  } catch (error) {
    notifyError(error);
  }
}

async function toggleStatus(row: ShopVO) {
  const target = row.status === 1 ? 0 : 1;
  const disabling = target === 0;
  try {
    await ElMessageBox.confirm(
      disabling
        ? t("shop.confirmDisable", { name: row.shopName })
        : t("shop.confirmEnable", { name: row.shopName }),
      disabling ? t("shop.disableTitle") : t("shop.enableTitle"),
      { type: "warning" }
    );
  } catch {
    return;
  }
  try {
    await updateShopStatus(row.id, target);
    ElMessage.success(disabling ? t("shop.disabled") : t("shop.enabled"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

async function changeSource(row: ShopVO) {
  try {
    if (row.isSource) await clearSourceShop(row.id);
    else await setSourceShop(row.id);
    ElMessage.success(row.isSource ? t("shop.sourceCleared") : t("shop.sourceSet"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

async function changePool(row: ShopVO) {
  try {
    if (row.copyPoolStatus === "JOINED") await exitCopyPool(row.id);
    else await joinCopyPool(row.id);
    ElMessage.success(
      row.copyPoolStatus === "JOINED" ? t("shop.poolExited") : t("shop.poolJoined")
    );
    await load();
  } catch (error) {
    notifyError(error);
  }
}

async function refreshCapacity(row: ShopVO) {
  refreshingId.value = row.id;
  try {
    await refreshShopCapacity(row.id);
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    refreshingId.value = null;
  }
}

async function checkApis(row: ShopVO) {
  try {
    await checkShopApis(row.id);
    await load();
  } catch (error) {
    notifyError(error);
  }
}

const ineligibleText = (reason: CopyIneligibleReason | null): string => {
  if (!reason) return "";
  const labels: Record<CopyIneligibleReason, string> = {
    DISABLED: t("shop.reasonDisabled"),
    UNAUTHORIZED: t("shop.reasonUnauthorized"),
    AUTH_EXPIRED: t("shop.reasonAuthExpired"),
    SOURCE_SHOP: t("shop.reasonSource"),
    POOL_EXITED: t("shop.reasonPoolExited"),
    CAPACITY_FULL: t("shop.reasonCapacityFull")
  };
  return labels[reason];
};

function apiStatusText(ok: boolean | null): string {
  if (ok === null) return t("shop.apiUnchecked");
  return ok ? t("shop.apiOk") : t("shop.apiFailed");
}

function rowActions(row: ShopVO): RowAction[] {
  return [
    {
      key: "authorize",
      label: t("shop.authorize"),
      testId: `authorize-${row.id}`,
      hidden: !canManage.value,
      onClick: () => void authorize(row)
    },
    {
      key: "sync-order",
      label: t("shop.pullOrders"),
      testId: `sync-order-${row.id}`,
      hidden: !canSyncOrders.value,
      onClick: () => void sync(row, "ORDER")
    },
    {
      key: "sync-refund",
      label: t("shop.pullRefunds"),
      testId: `sync-refund-${row.id}`,
      hidden: !canSyncOrders.value,
      onClick: () => void sync(row, "REFUND")
    },
    {
      key: "edit",
      label: t("common.edit"),
      testId: `edit-${row.id}`,
      hidden: !canManage.value,
      onClick: () => openEdit(row)
    },
    {
      key: "status",
      label: row.status === 1 ? t("common.disable") : t("common.enable"),
      testId: `status-${row.id}`,
      type: row.status === 1 ? "danger" : "success",
      hidden: !canManage.value,
      onClick: () => void toggleStatus(row)
    },
    {
      key: "source",
      label: row.isSource ? t("shop.clearSource") : t("shop.setSource"),
      testId: `source-${row.id}`,
      hidden: !canManage.value,
      onClick: () => void changeSource(row)
    },
    {
      key: "copy-pool",
      label: row.copyPoolStatus === "JOINED" ? t("shop.exitPool") : t("shop.joinPool"),
      testId: `copy-pool-${row.id}`,
      hidden: !canManage.value,
      disabled: row.isSource,
      tooltip: row.isSource ? t("shop.sourceCannotJoinPool") : undefined,
      onClick: () => void changePool(row)
    },
    {
      key: "api-check",
      label: t("shop.checkApis"),
      testId: `api-check-${row.id}`,
      hidden: !canManage.value,
      onClick: () => void checkApis(row)
    }
  ];
}

function authStatusText(row: ShopVO): string {
  if (row.authStatus === "AUTHORIZED") return t("shop.authAuthorized");
  if (row.authStatus === "EXPIRED") return t("shop.authExpired");
  return t("shop.authUnauthorized");
}

// Owner's 10/24 adjustment: only these shop actions move into More.
const MORE_ACTIONS = new Set(["authorize", "source", "copy-pool", "status", "api-check"]);
function inlineActions(row: ShopVO): RowAction[] {
  return rowActions(row).filter(action => !MORE_ACTIONS.has(action.key));
}
function moreActions(row: ShopVO): RowAction[] {
  return rowActions(row).filter(action => MORE_ACTIONS.has(action.key) && !action.hidden);
}
function runMoreAction(row: ShopVO, key: string): void {
  const action = moreActions(row).find(item => item.key === key);
  if (action && !action.disabled) action.onClick?.();
}

onMounted(() => {
  const params = new URLSearchParams(window.location.search);
  const authResult = params.get("weidianAuth");
  if (authResult === "success") {
    ElMessage.success(t("shop.authSuccess"));
    params.delete("weidianAuth");
    params.delete("reason");
    window.history.replaceState(
      {},
      "",
      `${window.location.pathname}${params.size ? `?${params}` : ""}`
    );
  } else if (authResult === "failed") {
    ElMessage.error(t("shop.authFailed"));
    params.delete("weidianAuth");
    params.delete("reason");
    window.history.replaceState(
      {},
      "",
      `${window.location.pathname}${params.size ? `?${params}` : ""}`
    );
  }
  void load();
});
</script>

<template>
  <div class="list-page">
    <ListSearch :chips="chips" @search="search" @reset="reset" @remove="removeChip">
      <template #primary>
        <el-form-item :label="t('shop.code')">
          <el-input v-model="query.shopCode" clearable data-test="search-shop-code" />
        </el-form-item>
        <el-form-item :label="t('shop.name')">
          <el-input v-model="query.shopName" clearable data-test="search-shop-name" />
        </el-form-item>
        <el-form-item :label="t('shop.channel')">
          <el-select v-model="query.channel" clearable data-test="search-channel">
            <el-option
              :label="t('shop.channelWeidian')"
              value="WEIDIAN"
              data-test="shop-filter-weidian"
            />
          </el-select>
        </el-form-item>
      </template>
      <template #actions>
        <el-button v-if="canManage" data-test="weidian-app-config" @click="openAppConfig">
          {{ t("shop.weidianApp.configure") }}
        </el-button>
        <el-button v-if="canManage" type="primary" data-test="create" @click="openCreate">
          {{ t("shop.create") }}
        </el-button>
      </template>
    </ListSearch>

    <DataTable
      table-id="shops"
      :rows="rows"
      :columns="columns"
      :loading="loading"
      :total="total"
      :page="page"
      :size="size"
      @refresh="load"
      @update:page="
        (value: number) => {
          page = value;
          void load();
        }
      "
      @update:size="
        (value: number) => {
          size = value;
          page = 1;
          void load();
        }
      "
    >
      <template #cell-shopCode="{ row }"><CopyText :value="asShop(row).shopCode" /></template>
      <template #cell-channel
        ><span>{{ t("shop.channelWeidian") }}</span></template
      >
      <template #cell-source="{ row }">
        <el-tag v-if="asShop(row).isSource" type="warning">{{ t("shop.source") }}</el-tag>
        <span v-else>—</span>
      </template>
      <template #cell-copyPool="{ row }">
        <span class="inline-status">
          <el-tag v-if="asShop(row).copyPoolStatus === 'NOT_APPLICABLE'" type="info">{{
            t("shop.poolNotApplicable")
          }}</el-tag>
          <el-tag v-else-if="asShop(row).copyPoolStatus === 'JOINED'" type="success">{{
            t("shop.poolJoinedLabel")
          }}</el-tag>
          <el-tag v-else type="info">{{ t("shop.poolExitedLabel") }}</el-tag>
          <el-tooltip
            v-if="!asShop(row).copyEligible"
            :content="ineligibleText(asShop(row).copyIneligibleReason)"
            placement="top"
          >
            <el-icon
              class="reason-icon"
              :aria-label="ineligibleText(asShop(row).copyIneligibleReason)"
              ><WarningFilled
            /></el-icon>
          </el-tooltip>
        </span>
      </template>
      <template #cell-weidianShopId="{ row }"
        ><span>{{ asShop(row).weidianShopId || "—" }}</span></template
      >
      <template #cell-authStatus="{ row }">
        <el-tag
          :type="
            asShop(row).authStatus === 'AUTHORIZED'
              ? 'success'
              : asShop(row).authStatus === 'EXPIRED'
                ? 'danger'
                : 'info'
          "
        >
          {{ authStatusText(asShop(row)) }}
        </el-tag>
      </template>
      <template #cell-authExpiresAt="{ row }">{{
        formatInstantToMinute(asShop(row).authExpiresAt)
      }}</template>
      <template #cell-apiStatus="{ row }">
        <div class="api-status-list">
          <span
            v-for="kind in ['product', 'order', 'refund'] as const"
            :key="kind"
            class="api-status-item"
          >
            <el-tooltip
              :disabled="!asShop(row).apiStatus[kind].error"
              :content="asShop(row).apiStatus[kind].error ?? ''"
              placement="top"
            >
              <span>
                {{ t(`shop.api${kind[0].toUpperCase()}${kind.slice(1)}`) }}
                <el-tag
                  :type="
                    asShop(row).apiStatus[kind].ok === true
                      ? 'success'
                      : asShop(row).apiStatus[kind].ok === false
                        ? 'danger'
                        : 'info'
                  "
                  :data-test="`api-${kind}-${asShop(row).id}`"
                  >{{ apiStatusText(asShop(row).apiStatus[kind].ok) }}</el-tag
                >
              </span>
            </el-tooltip>
          </span>
        </div>
      </template>
      <template #cell-capacity="{ row }">
        <span
          class="capacity-cell"
          :class="{ 'capacity-cell--full': asShop(row).currentItems >= asShop(row).maxItems }"
        >
          {{ asShop(row).currentItems }} / {{ asShop(row).maxItems }}
          <el-tooltip :content="t('shop.refreshCapacity')" placement="top">
            <el-button
              v-if="canManage"
              link
              :loading="refreshingId === asShop(row).id"
              :aria-label="t('shop.refreshCapacity')"
              :data-test="`refresh-capacity-${asShop(row).id}`"
              @click="refreshCapacity(asShop(row))"
            >
              <el-icon><Refresh /></el-icon>
            </el-button>
          </el-tooltip>
        </span>
      </template>
      <template #cell-status="{ row }">
        <el-tag :type="asShop(row).status === 1 ? 'success' : 'info'">
          {{ asShop(row).status === 1 ? t("common.enabled") : t("common.disabled") }}
        </el-tag>
      </template>
      <template #actions="{ row }">
        <div class="shop-actions">
          <RowActions :actions="inlineActions(asShop(row))" />
          <el-dropdown
            v-if="moreActions(asShop(row)).length"
            trigger="click"
            @command="runMoreAction(asShop(row), $event)"
          >
            <el-button link :data-test="`more-${asShop(row).id}`">{{ t("common.more") }}</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="action in moreActions(asShop(row))"
                  :key="action.key"
                  :command="action.key"
                  :disabled="action.disabled"
                  :data-test="action.testId"
                  :title="action.tooltip"
                >
                  {{ action.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </template>
    </DataTable>

    <el-dialog
      v-model="formVisible"
      :title="editing ? t('shop.editTitle') : t('shop.createTitle')"
      width="min(520px, calc(100vw - 24px))"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        data-test="shop-form"
      >
        <el-form-item v-if="editing" :label="t('shop.code')">
          <el-input v-model="form.shopCode" disabled data-test="form-shop-code" />
        </el-form-item>
        <el-form-item prop="shopName" :label="t('shop.name')" required>
          <el-input v-model="form.shopName" maxlength="64" data-test="form-shop-name" />
        </el-form-item>
        <el-form-item prop="channel" :label="t('shop.channel')" required>
          <el-select v-model="form.channel" data-test="form-channel" style="width: 100%">
            <el-option :label="t('shop.channelWeidian')" value="WEIDIAN" />
          </el-select>
        </el-form-item>
        <el-form-item prop="maxItems" :label="t('shop.maxItems')" required>
          <el-input-number
            v-model="form.maxItems"
            :min="1"
            :max="1000000"
            :step="1000"
            data-test="form-max-items"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="t('shop.weidianId')">
          <el-input v-model="form.weidianShopId" maxlength="64" data-test="form-weidian-id" />
        </el-form-item>
        <el-form-item :label="t('common.remark')">
          <el-input v-model="form.remark" type="textarea" maxlength="500" data-test="form-remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button data-test="form-cancel" @click="formVisible = false">{{
          t("common.cancel")
        }}</el-button>
        <el-button type="primary" :loading="saving" data-test="form-save" @click="save">{{
          t("common.save")
        }}</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="appDialogVisible"
      :title="t('shop.weidianApp.title')"
      width="min(560px, calc(100vw - 24px))"
      :close-on-click-modal="false"
      data-test="weidian-app-dialog"
    >
      <el-skeleton v-if="appLoading" :rows="5" animated />
      <el-form
        v-else
        ref="appFormRef"
        :model="appForm"
        :rules="appRules"
        label-position="top"
        data-test="weidian-app-form"
      >
        <el-form-item :label="t('shop.weidianApp.callbackUrl')">
          <CopyText :value="appStatus?.callbackUrl" />
        </el-form-item>
        <p class="callback-hint">{{ t("shop.weidianApp.callbackHint") }}</p>
        <el-alert
          v-if="callbackUrlInvalid"
          :title="t('shop.weidianApp.callbackInvalid')"
          type="warning"
          :closable="false"
          show-icon
          data-test="callback-invalid"
        />
        <el-form-item prop="appKey" :label="t('shop.weidianApp.appKey')" required>
          <el-input
            v-model="appForm.appKey"
            maxlength="64"
            show-word-limit
            autocomplete="off"
            data-test="weidian-app-key"
          />
        </el-form-item>
        <el-form-item prop="appSecret" :label="t('shop.weidianApp.appSecret')" required>
          <el-input
            v-model="appForm.appSecret"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="
              appStatus?.secretConfigured ? t('shop.weidianApp.secretConfiguredHint') : ''
            "
            data-test="weidian-app-secret"
          />
        </el-form-item>
        <div class="app-status" data-test="weidian-app-status">
          <el-tag :type="appStatus?.secretConfigured ? 'success' : 'info'">
            {{
              appStatus?.secretConfigured
                ? t("shop.weidianApp.configured")
                : t("shop.weidianApp.notConfigured")
            }}
          </el-tag>
          <span
            >{{ t("shop.weidianApp.lastUpdated") }}:
            {{ formatInstantToMinute(appStatus?.updateTime) }}</span
          >
        </div>
      </el-form>
      <template #footer>
        <el-button data-test="weidian-app-cancel" @click="appDialogVisible = false">
          {{ t("common.cancel") }}
        </el-button>
        <el-button
          type="primary"
          :loading="appSaving"
          :disabled="appLoading"
          data-test="weidian-app-save"
          @click="saveAppConfig"
        >
          {{ t("common.save") }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.shop-actions {
  display: inline-flex;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.inline-status,
.capacity-cell,
.api-status-list {
  display: inline-flex;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.api-status-list {
  flex-wrap: wrap;
}
.api-status-item--failed,
.capacity-cell--full {
  color: var(--tcrm-color-danger);
}
.callback-hint {
  margin: 0 0 var(--tcrm-space-4);
  color: var(--tcrm-color-text-secondary);
}
.app-status {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-3);
  margin-bottom: var(--tcrm-space-4);
  color: var(--tcrm-color-text-secondary);
}
.reason-icon {
  color: var(--tcrm-color-text-secondary);
}
</style>
