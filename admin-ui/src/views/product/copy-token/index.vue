<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  listCopyTokens,
  revealCopyToken,
  revokeCopyToken,
  type CopyTokenDisplayStatus
} from "@/api/kol/copy-token";
import { listKols } from "@/api/kol/kol";
import type { CopyTokenVO, KolVO } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import ListSearch, { type SearchChip } from "@/components/ListSearch.vue";
import { useUserStore } from "@/stores/user";
import { copyText } from "@/utils/copy";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import { accessOf } from "@/utils/permission";
import { readPageSize } from "@/utils/table-prefs";
import CopyTokenCreateDialog from "./CopyTokenCreateDialog.vue";

defineOptions({ name: "CopyTokenPage" });

const { t } = useI18n();
const me = useUserStore();
const canManage = computed(() => accessOf(me.user).can("kol:copy-token"));

const STATUSES: CopyTokenDisplayStatus[] = ["ACTIVE", "EXHAUSTED", "EXPIRED", "REVOKED"];

const loading = ref(false);
const rows = ref<CopyTokenVO[]>([]);
const total = ref(0);
const createVisible = ref(false);
const revealing = ref<string | null>(null);
const kolOptions = ref<KolVO[]>([]);
const kolSearching = ref(false);

const query = reactive<{
  page: number;
  size: number;
  hint: string;
  kolId?: string;
  targetChannel?: string;
  status?: CopyTokenDisplayStatus;
}>({
  page: 1,
  size: readPageSize("copy-tokens", 20),
  hint: ""
});

const applied = ref({
  hint: "",
  kolId: undefined as string | undefined,
  targetChannel: undefined as string | undefined,
  status: undefined as CopyTokenDisplayStatus | undefined
});

function asRow(row: object): CopyTokenVO {
  return row as CopyTokenVO;
}

function rowId(row: object): string {
  const value = (row as Record<string, unknown>)["id"];
  return value == null ? "" : String(value);
}

function statusTag(status: CopyTokenDisplayStatus): "success" | "warning" | "info" | "danger" {
  if (status === "ACTIVE") return "success";
  if (status === "EXHAUSTED") return "warning";
  if (status === "EXPIRED") return "info";
  return "danger";
}

/** Map display status to locale key without dynamic casting issues. */
function statusText(status: CopyTokenDisplayStatus): string {
  switch (status) {
    case "ACTIVE":
      return t("copyToken.statusActive");
    case "EXHAUSTED":
      return t("copyToken.statusExhausted");
    case "EXPIRED":
      return t("copyToken.statusExpired");
    case "REVOKED":
      return t("copyToken.statusRevoked");
  }
}

const columns = computed<DataColumn[]>(() => {
  const list: DataColumn[] = [
    { key: "hint", label: t("copyToken.hint"), minWidth: 200 },
    { key: "kol", label: t("copyToken.kol"), minWidth: 160 },
    { key: "channel", label: t("copyToken.channel"), width: 90 },
    { key: "shop", label: t("copyToken.shop"), minWidth: 120 },
    { key: "usage", label: t("copyToken.usage"), width: 110 },
    { key: "validMinutes", label: t("copyToken.validMinutes"), width: 100 },
    { key: "expiresAt", label: t("copyToken.expiresAt"), width: 170 },
    { key: "status", label: t("common.status"), width: 100 },
    { key: "createTime", label: t("common.createdAt"), width: 170 }
  ];
  if (canManage.value) {
    list.push({ key: "actions", label: t("common.actions"), width: 100 });
  }
  return list;
});

const chips = computed<SearchChip[]>(() => {
  const list: SearchChip[] = [];
  if (applied.value.hint) {
    list.push({ key: "hint", label: t("copyToken.hint"), text: applied.value.hint });
  }
  if (applied.value.kolId) {
    const kol = kolOptions.value.find(k => k.id === applied.value.kolId);
    list.push({
      key: "kol",
      label: t("copyToken.kol"),
      text: kol ? `${kol.name} (${kol.kolCode})` : applied.value.kolId
    });
  }
  if (applied.value.targetChannel) {
    list.push({
      key: "channel",
      label: t("copyToken.channel"),
      text: t("copyToken.channelWeidian")
    });
  }
  if (applied.value.status) {
    list.push({
      key: "status",
      label: t("common.status"),
      text: statusText(applied.value.status)
    });
  }
  return list;
});

async function load() {
  if (!canManage.value) {
    rows.value = [];
    total.value = 0;
    return;
  }
  loading.value = true;
  try {
    const page = await listCopyTokens({
      page: query.page,
      size: query.size,
      hint: applied.value.hint || undefined,
      kolId: applied.value.kolId,
      targetChannel: applied.value.targetChannel,
      status: applied.value.status
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
    hint: query.hint.trim(),
    kolId: query.kolId,
    targetChannel: query.targetChannel,
    status: query.status
  };
  query.page = 1;
  void load();
}

function reset() {
  query.hint = "";
  query.kolId = undefined;
  query.targetChannel = undefined;
  query.status = undefined;
  search();
}

function removeChip(key: string) {
  if (key === "hint") query.hint = "";
  if (key === "kol") query.kolId = undefined;
  if (key === "channel") query.targetChannel = undefined;
  if (key === "status") query.status = undefined;
  search();
}

function onPage(page: number) {
  query.page = page;
  void load();
}

function onSize(size: number) {
  query.size = size;
  query.page = 1;
  void load();
}

async function searchKols(keyword: string) {
  if (!keyword.trim()) {
    kolOptions.value = [];
    return;
  }
  kolSearching.value = true;
  try {
    const page = await listKols({ keyword: keyword.trim(), page: 1, size: 20 });
    kolOptions.value = page.items;
  } catch (error) {
    notifyError(error);
  } finally {
    kolSearching.value = false;
  }
}

function clickReveal(event: Event) {
  const id = (event.currentTarget as HTMLElement | null)?.getAttribute("data-id");
  if (!id) return;
  void (async () => {
    revealing.value = id;
    try {
      const { token: plain } = await revealCopyToken(id);
      const ok = await copyText(plain);
      if (ok) ElMessage.success(t("copyToken.copied"));
      else ElMessage.error(t("copy.failed"));
    } catch (error) {
      notifyError(error);
    } finally {
      revealing.value = null;
    }
  })();
}

async function clickRevoke(event: Event) {
  const id = (event.currentTarget as HTMLElement | null)?.getAttribute("data-id");
  if (!id) return;
  try {
    await ElMessageBox.confirm(t("copyToken.revokeConfirm"), t("copyToken.revoke"), {
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    await revokeCopyToken(id);
    ElMessage.success(t("copyToken.revokeDone"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

function canRevoke(row: CopyTokenVO): boolean {
  return row.displayStatus !== "REVOKED";
}

function channelText(channel: string): string {
  return channel === "WEIDIAN" ? t("copyToken.channelWeidian") : channel;
}

onMounted(() => {
  void load();
});
</script>

<template>
  <div v-if="!canManage" class="empty">{{ t("errors.AUTH_FORBIDDEN") }}</div>
  <div v-else class="list-page">
    <ListSearch :chips="chips" has-more @search="search" @reset="reset" @remove="removeChip">
      <template #filters>
        <el-form-item :label="t('copyToken.hint')">
          <el-input
            v-model="query.hint"
            clearable
            :placeholder="t('copyToken.hintPlaceholder')"
            data-test="filter-hint"
          />
        </el-form-item>
        <el-form-item :label="t('copyToken.kol')">
          <el-select
            v-model="query.kolId"
            filterable
            remote
            clearable
            :remote-method="searchKols"
            :loading="kolSearching"
            :placeholder="t('copyToken.kolPlaceholder')"
            data-test="filter-kol"
            style="width: 220px"
          >
            <el-option
              v-for="kol in kolOptions"
              :key="kol.id"
              :label="`${kol.name} (${kol.kolCode})`"
              :value="kol.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('copyToken.channel')">
          <el-select
            v-model="query.targetChannel"
            clearable
            data-test="filter-channel"
            style="width: 140px"
          >
            <el-option :label="t('copyToken.channelWeidian')" value="WEIDIAN" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('common.status')">
          <el-select
            v-model="query.status"
            clearable
            data-test="filter-status"
            style="width: 140px"
          >
            <el-option
              v-for="status in STATUSES"
              :key="status"
              :label="statusText(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
      </template>
      <template #actions>
        <el-button type="primary" data-test="create-token" @click="createVisible = true">
          {{ t("copyToken.create") }}
        </el-button>
      </template>
    </ListSearch>

    <DataTable
      table-id="copy-tokens"
      :columns="columns"
      :rows="rows"
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
      <template #cell-hint="{ row }">
        <span class="">{{ asRow(row).tokenHint }}</span>
        <button
          type="button"
          class="link-btn"
          data-test="reveal"
          :data-id="rowId(row)"
          :disabled="revealing === rowId(row)"
          @click="clickReveal"
        >
          {{ t("common.copy") }}
        </button>
      </template>
      <template #cell-kol="{ row }">
        <span>{{ asRow(row).kolName || "-" }}</span>
        <el-tag v-if="asRow(row).kolCode" size="small" class="kol-code" data-test="kol-code">
          {{ asRow(row).kolCode }}
        </el-tag>
      </template>
      <template #cell-channel="{ row }">
        {{ channelText(asRow(row).targetChannel) }}
      </template>
      <template #cell-shop="{ row }">
        {{ asRow(row).targetShopName || t("copyToken.shopAuto") }}
      </template>
      <template #cell-usage="{ row }">
        <span data-test="usage">{{ asRow(row).used }} / {{ asRow(row).quota }}</span>
      </template>
      <template #cell-validMinutes="{ row }">{{ asRow(row).validMinutes }}</template>
      <template #cell-expiresAt="{ row }">{{ formatInstant(asRow(row).expiresAt) }}</template>
      <template #cell-status="{ row }">
        <el-tag :type="statusTag(asRow(row).displayStatus)" data-test="status">
          {{ statusText(asRow(row).displayStatus) }}
        </el-tag>
      </template>
      <template #cell-createTime="{ row }">{{ formatInstant(asRow(row).createTime) }}</template>
      <template #actions="{ row }">
        <button
          v-if="canRevoke(asRow(row))"
          type="button"
          class="link-btn link-btn--danger"
          data-test="revoke"
          :data-id="rowId(row)"
          @click="clickRevoke"
        >
          {{ t("copyToken.revoke") }}
        </button>
      </template>
    </DataTable>

    <CopyTokenCreateDialog v-model="createVisible" @created="load" />
  </div>
</template>

<style scoped>
.empty {
  padding: 24px;
}
.kol-code {
  margin-left: 6px;
}
.link-btn {
  margin-left: 6px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
}
.link-btn:disabled {
  cursor: wait;
  opacity: 0.6;
}
.link-btn--danger {
  color: var(--el-color-danger);
}
</style>
