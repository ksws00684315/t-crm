<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { getKol, inviteKol } from "@/api/kol/kol";
import type { BdHistoryVO, KolDetailVO } from "@/api/types";
import CopyText from "@/components/CopyText.vue";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import { useUserStore } from "@/stores/user";
import { formatInstant } from "@/utils/format";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";
import { kolAbilities } from "@/utils/permission";
import CopyTokenCreateDialog from "@/views/product/copy-token/CopyTokenCreateDialog.vue";
import PromoSheetUploadDialog from "./PromoSheetUploadDialog.vue";
import { formatRate } from "@/utils/rate";
import { relationStatusLabelKey, relationTagType } from "@/utils/promotion";

defineOptions({ name: "KolDetailDrawer" });

const { t } = useI18n();
const me = useUserStore();

const props = defineProps<{ modelValue: boolean; kolId: string | null }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  /** The viewer asked to edit the promotion settings; the page opens the config drawer. */
  (event: "openConfig", kol: KolDetailVO): void;
}>();

const loading = ref(false);
const inviting = ref(false);
const detail = ref<KolDetailVO | null>(null);
const copyTokenVisible = ref(false);
const promoSheetVisible = ref(false);

const promoSheetButton = computed(() => {
  const kol = detail.value;
  return !!kol && kolAbilities(me.user).canWrite;
});

/** Promotion settings stay editable after promoting (ADR-023 §3), so offer them here too. */
const canEditConfig = computed(
  () => kolAbilities(me.user).canWrite && detail.value?.reviewStatus === "APPROVED"
);

const inviteVisible = computed(() => {
  const kol = detail.value;
  if (!kol || !kolAbilities(me.user).canWrite) return false;
  return kol.accountStatus === 0 && kol.registerSource === 1;
});

const copyTokenVisibleBtn = computed(() => {
  const kol = detail.value;
  return !!kol && kol.status === 1 && kolAbilities(me.user).canCopyToken;
});

watch(
  () => [props.modelValue, props.kolId] as const,
  async ([open, id]) => {
    if (!open || !id) return;
    loading.value = true;
    detail.value = null;
    try {
      detail.value = await getKol(id);
    } catch (error) {
      notifyError(error);
      emit("update:modelValue", false);
    } finally {
      loading.value = false;
    }
  }
);

function bdName(row: BdHistoryVO): string {
  return row.bdUserName ?? (row.bdUserId ? row.bdUserId : t("common.unassigned"));
}

function asHistory(row: object): BdHistoryVO {
  return row as BdHistoryVO;
}

function sourceText(source: 1 | 2 | undefined): string {
  if (source === 2) return t("kol.sourceSelf");
  if (source === 1) return t("kol.sourceAdmin");
  return "-";
}

function accountText(status: 0 | 1 | undefined): string {
  if (status === 0) return t("kol.accountInactive");
  if (status === 1) return t("kol.accountActive");
  return "-";
}

async function reload() {
  if (!props.kolId) return;
  detail.value = await getKol(props.kolId);
}

async function sendInvite() {
  if (!props.kolId) return;
  inviting.value = true;
  try {
    await inviteKol(props.kolId);
    ElMessage.success(t("kol.inviteSent"));
    await reload();
  } catch (error) {
    notifyError(error);
    if (error instanceof ApiError && error.errorCode === "KOL_ALREADY_ACTIVATED") {
      try {
        await reload();
      } catch (reloadError) {
        notifyError(reloadError);
      }
    }
  } finally {
    inviting.value = false;
  }
}

const historyColumns = computed<DataColumn[]>(() => [
  { key: "bd", label: t("kol.bdColumn"), minWidth: 120 },
  { key: "startedAt", label: t("kol.startedAt"), width: 170 },
  { key: "endedAt", label: t("kol.endedAt"), width: 170 }
]);
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="t('kol.detailTitle')"
    size="640px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('kol.code')">
            <CopyText :value="detail.kolCode" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.name')">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.email')">
            <CopyText :value="detail.email" />
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.country')">{{
            detail.country || "-"
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('kol.whatsapp')">
            <CopyText v-if="detail.whatsapp" :value="detail.whatsapp" />
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('kol.phone')">
            <CopyText v-if="detail.phone" :value="detail.phone" />
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.linkTemplateName" :label="t('kol.agentPlatform')">
            {{ detail.linkTemplateName }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('promotion.creatorInviteCode')">
            <CopyText v-if="detail.inviteCode" :value="detail.inviteCode" />
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('kol.sheetUrl')">
            <template v-if="detail.sheetUrl">
              <a
                data-test="detail-sheet"
                :href="detail.sheetUrl"
                target="_blank"
                rel="noopener noreferrer"
                >{{ detail.sheetUrl }}</a
              >
              <CopyText :value="detail.sheetUrl" />
            </template>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('kol.registerSource')">
            {{ sourceText(detail.registerSource) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('kol.relationStatus')">
            <el-tag
              :type="relationTagType(detail.displayRelationStatus)"
              data-test="detail-display-status"
            >
              {{ t(relationStatusLabelKey(detail.displayRelationStatus)) }}
            </el-tag>
            <el-button
              v-if="canEditConfig"
              type="primary"
              link
              data-test="detail-edit-config"
              @click="detail && emit('openConfig', detail)"
            >
              {{ t("promotion.configTitle") }}
            </el-button>
          </el-descriptions-item>
          <el-descriptions-item :label="t('promotion.platforms')">
            <div v-for="platform in detail.platforms" :key="platform.templateId">
              <a :href="platform.address" target="_blank" rel="noopener noreferrer"
                >{{ platform.templateName }}: {{ platform.address }}</a
              >
            </div>
          </el-descriptions-item>
          <el-descriptions-item :label="t('kol.account')">
            <span data-test="account-status">{{ accountText(detail.accountStatus) }}</span>
            <el-button
              v-if="inviteVisible"
              type="primary"
              link
              data-test="detail-invite"
              :loading="inviting"
              @click="sendInvite"
            >
              {{ t("kol.invite") }}
            </el-button>
          </el-descriptions-item>
          <el-descriptions-item v-if="copyTokenVisibleBtn" :label="t('copyToken.title')">
            <el-button
              type="primary"
              link
              data-test="detail-copy-token"
              @click="copyTokenVisible = true"
            >
              {{ t("kol.generateCopyToken") }}
            </el-button>
          </el-descriptions-item>
          <el-descriptions-item v-if="promoSheetButton" :label="t('promoSheet.title')">
            <el-button
              type="primary"
              link
              data-test="detail-promo-sheet"
              @click="promoSheetVisible = true"
            >
              {{ t("promoSheet.open") }}
            </el-button>
          </el-descriptions-item>
          <el-descriptions-item :label="t('kol.bd')">
            {{ detail.bdUserName ?? t("common.unassigned") }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('kol.rateLabel')">
            {{ formatRate(detail.defaultRate) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.status')">
            {{ detail.status === 1 ? t("common.enabled") : t("common.disabled") }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('kol.social')">
            <div v-if="detail.socialAccounts.length === 0">-</div>
            <div v-for="(account, index) in detail.socialAccounts" :key="index">
              {{ account.platform }}: {{ account.handle }}
              <el-tag v-if="account.primary" size="small" data-test="primary-tag">{{
                t("kol.primaryTag")
              }}</el-tag>
              <template v-if="account.followers">{{
                t("kol.followers", { count: account.followers })
              }}</template>
              <a v-if="account.url" :href="account.url" target="_blank" rel="noopener noreferrer">
                {{ t("kol.homepage") }}
              </a>
              <CopyText v-if="account.url" :value="account.url" />
            </div>
          </el-descriptions-item>
          <el-descriptions-item :label="t('common.remark')">{{
            detail.remark || "-"
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('common.createdAt')">
            {{ formatInstant(detail.createTime) }}
          </el-descriptions-item>
        </el-descriptions>
        <h4 class="title">{{ t("kol.bdHistory") }}</h4>
        <DataTable
          plain
          table-id="kol-bd-history"
          :rows="detail.bdHistory"
          :columns="historyColumns"
          :total="detail.bdHistory.length"
          :paged="false"
        >
          <template #cell-bd="{ row }">{{ bdName(asHistory(row)) }}</template>
          <template #cell-startedAt="{ row }">{{
            formatInstant(asHistory(row).startTime)
          }}</template>
          <template #cell-endedAt="{ row }">
            {{ asHistory(row).endTime ? formatInstant(asHistory(row).endTime) : t("kol.current") }}
          </template>
        </DataTable>
      </template>
    </div>
    <CopyTokenCreateDialog
      v-if="detail"
      v-model="copyTokenVisible"
      :preset-kol-id="detail.id"
      :preset-kol-label="`${detail.name} (${detail.kolCode})`"
    />
    <PromoSheetUploadDialog
      v-if="detail"
      v-model="promoSheetVisible"
      :kol-id="detail.id"
      :kol-label="`${detail.name} (${detail.kolCode})`"
    />
  </el-drawer>
</template>

<style scoped>
.title {
  margin: 20px 0 8px;
}
</style>
