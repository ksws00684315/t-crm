<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { ElMessage } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { createCopyToken } from "@/api/kol/copy-token";
import { listKols } from "@/api/kol/kol";
import { listCopyChannels } from "@/api/product/shop";
import type { CopyChannelVO, CreatedCopyTokenVO, KolVO } from "@/api/types";
import { copyText, truncateCopy } from "@/utils/copy";
import { formatNameWithCode } from "@/utils/format";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "CopyTokenCreateDialog" });

const props = defineProps<{
  modelValue: boolean;
  /** When set, the KOL field is prefilled and locked. */
  presetKolId?: string | null;
  presetKolLabel?: string | null;
  presetKolName?: string | null;
  presetKolCode?: string | null;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "created"): void;
}>();

const { t } = useI18n();
const saving = ref(false);
const result = ref<CreatedCopyTokenVO | null>(null);
const kolOptions = ref<KolVO[]>([]);
const kolSearching = ref(false);
const channels = ref<CopyChannelVO[]>([]);
const channelLoading = ref(false);
const channelLoadFailed = ref(false);
const NO_CAPACITY_CHANNEL: CopyChannelVO = {
  channel: "WEIDIAN",
  remainingCapacity: 0,
  shops: []
};

const form = reactive<{
  kolId: string | null;
  targetChannel: "" | "WEIDIAN";
  targetShopId: string | null;
  quota: number | null;
  validMinutes: number | null;
}>({
  kolId: null,
  targetChannel: "",
  targetShopId: null,
  quota: null,
  validMinutes: null
});

const open = computed({
  get: () => props.modelValue,
  set: value => emit("update:modelValue", value)
});
const selectedChannel = computed(() =>
  channels.value.find(item => item.channel === form.targetChannel)
);
const channelOptions = computed(() =>
  channels.value.length || channelLoadFailed.value ? channels.value : [NO_CAPACITY_CHANNEL]
);
const eligibleShops = computed(() => selectedChannel.value?.shops ?? []);
const availableCapacity = computed(() => {
  if (form.targetShopId) {
    return eligibleShops.value.find(shop => shop.id === form.targetShopId)?.remainingCapacity ?? 0;
  }
  return selectedChannel.value?.remainingCapacity ?? 0;
});
const capacityExceeded = computed(
  () => form.quota !== null && form.quota > availableCapacity.value
);

function setDefaults(channel: CopyChannelVO | undefined) {
  form.quota = channel?.defaultQuota ?? null;
  form.validMinutes = channel?.defaultValidMinutes ?? null;
}

watch(
  () => props.modelValue,
  async openNow => {
    if (!openNow) return;
    result.value = null;
    form.kolId = props.presetKolId ?? null;
    form.targetChannel = "";
    form.targetShopId = null;
    form.quota = null;
    form.validMinutes = null;
    kolOptions.value = [];
    channelLoadFailed.value = false;
    if (props.presetKolId && props.presetKolLabel) {
      kolOptions.value = [
        {
          id: props.presetKolId,
          kolCode: props.presetKolCode ?? "",
          name: props.presetKolName ?? props.presetKolLabel,
          email: "",
          country: null,
          socialAccounts: [],
          bdUserId: null,
          bdUserName: null,
          defaultRate: null,
          status: 1,
          remark: null,
          whatsapp: null,
          phone: null,
          linkTemplateId: null,
          linkTemplateName: null,
          inviteCode: null,
          sheetUrl: null,
          registerSource: 1,
          accountStatus: 1,
          reviewStatus: "APPROVED",
          relationStatus: "PENDING_PROMOTION",
          displayRelationStatus: "PENDING_PROMOTION",
          loginStatus: "NORMAL",
          platforms: [],
          jumpTemplateId: null,
          configVersion: null,
          inviteTime: "",
          createTime: "",
          updateTime: ""
        }
      ];
    }
    channelLoading.value = true;
    try {
      channels.value = await listCopyChannels();
      const firstAvailable = channels.value.find(channel => channel.remainingCapacity > 0);
      if (firstAvailable) {
        form.targetChannel = firstAvailable.channel;
        setDefaults(firstAvailable);
      }
    } catch (error) {
      channelLoadFailed.value = true;
      notifyError(error);
    } finally {
      channelLoading.value = false;
    }
  }
);

watch(
  () => form.targetChannel,
  value => {
    form.targetShopId = null;
    setDefaults(channels.value.find(channel => channel.channel === value));
  }
);

async function searchKols(keyword: string) {
  if (props.presetKolId) return;
  if (!keyword.trim()) {
    kolOptions.value = [];
    return;
  }
  kolSearching.value = true;
  try {
    const page = await listKols({ keyword: keyword.trim(), page: 1, size: 100 });
    kolOptions.value = page.items.filter(kol => kol.status === 1);
  } catch (error) {
    notifyError(error);
  } finally {
    kolSearching.value = false;
  }
}

function kolLabel(kol: KolVO): string {
  return formatNameWithCode(kol.name, kol.kolCode);
}

function channelLabel(channel: CopyChannelVO): string {
  return channel.remainingCapacity > 0
    ? t("copyToken.channelCapacity", { capacity: channel.remainingCapacity })
    : t("copyToken.channelNoCapacity");
}

function shopLabel(shop: CopyChannelVO["shops"][number]): string {
  return t("copyToken.shopRemaining", {
    name: shop.shopName,
    code: shop.shopCode,
    capacity: shop.remainingCapacity
  });
}

async function submit() {
  if (!form.kolId) {
    ElMessage.error(t("copyToken.kolPlaceholder"));
    return;
  }
  if (!form.targetChannel) {
    ElMessage.error(t("copyToken.targetChannelRequired"));
    return;
  }
  if (form.quota === null || form.quota < 1) {
    ElMessage.error(t("copyToken.quotaRequired"));
    return;
  }
  if (form.validMinutes === null || form.validMinutes < 1) {
    ElMessage.error(t("copyToken.validMinutesRequired"));
    return;
  }
  if (capacityExceeded.value) {
    ElMessage.error(t("copyToken.capacityExceeded", { capacity: availableCapacity.value }));
    return;
  }
  saving.value = true;
  try {
    result.value = await createCopyToken({
      kolId: form.kolId,
      targetChannel: form.targetChannel,
      quota: form.quota,
      validMinutes: form.validMinutes,
      ...(form.targetShopId ? { targetShopId: form.targetShopId } : {})
    });
    emit("created");
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}

async function copyResult() {
  if (!result.value) return;
  const ok = await copyText(result.value.token);
  if (ok) ElMessage.success(t("copy.done", { text: truncateCopy(result.value.token) }));
  else ElMessage.error(t("copy.failed"));
}

function close() {
  open.value = false;
}
</script>

<template>
  <el-dialog
    v-model="open"
    :title="result ? t('copyToken.resultTitle') : t('copyToken.createTitle')"
    width="min(520px, calc(100vw - 24px))"
    destroy-on-close
    data-test="copy-token-create-dialog"
  >
    <template v-if="!result">
      <el-form label-position="top">
        <el-form-item :label="t('copyToken.kol')" required>
          <el-select
            v-model="form.kolId"
            filterable
            remote
            clearable
            :disabled="!!presetKolId"
            :remote-method="searchKols"
            :loading="kolSearching"
            :placeholder="t('copyToken.kolPlaceholder')"
            data-test="create-kol"
            style="width: 100%"
          >
            <el-option
              v-for="kol in kolOptions"
              :key="kol.id"
              :label="kolLabel(kol)"
              :value="kol.id"
              ><NameCode :name="kol.name" :code="kol.kolCode"
            /></el-option>
          </el-select>
        </el-form-item>
        <el-form-item :label="t('copyToken.channel')" required>
          <el-select
            v-model="form.targetChannel"
            :loading="channelLoading"
            :placeholder="t('copyToken.targetChannelRequired')"
            data-test="create-channel"
            style="width: 100%"
          >
            <el-option
              v-for="channel in channelOptions"
              :key="channel.channel"
              :label="channelLabel(channel)"
              :value="channel.channel"
              :disabled="channel.remainingCapacity <= 0"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('copyToken.shop')">
          <el-select
            v-model="form.targetShopId"
            clearable
            :disabled="!form.targetChannel"
            :placeholder="t('copyToken.shopAuto')"
            data-test="create-shop"
            style="width: 100%"
          >
            <el-option
              v-for="shop in eligibleShops"
              :key="shop.id"
              :label="shopLabel(shop)"
              :value="shop.id"
            />
          </el-select>
        </el-form-item>
        <p
          v-if="!channelLoadFailed && !channels.some(channel => channel.remainingCapacity > 0)"
          class="hint"
          data-test="no-copy-capacity"
        >
          {{ t("copyToken.shopAutoMissing") }}
        </p>
        <el-form-item :label="t('copyToken.quota')" required>
          <el-input-number
            v-model="form.quota"
            :min="1"
            data-test="create-quota"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="t('copyToken.validMinutes')" required>
          <el-input-number
            v-model="form.validMinutes"
            :min="1"
            data-test="create-minutes"
            style="width: 100%"
          />
        </el-form-item>
        <p v-if="capacityExceeded" class="capacity-error" data-test="capacity-error">
          {{ t("copyToken.capacityExceeded", { capacity: availableCapacity }) }}
        </p>
        <p class="hint">{{ t("copyToken.defaultsNote") }}</p>
      </el-form>
    </template>
    <template v-else>
      <p data-test="result-hint">{{ t("copyToken.resultHint") }}</p>
      <div class="result-token" data-test="result-token">{{ result.token }}</div>
      <el-button type="primary" data-test="result-copy" @click="copyResult">
        {{ t("copyToken.copyPlain") }}
      </el-button>
    </template>
    <template #footer>
      <el-button v-if="!result" data-test="create-cancel" @click="close">{{
        t("common.cancel")
      }}</el-button>
      <el-button
        v-if="!result"
        type="primary"
        :loading="saving"
        :disabled="!channels.some(channel => channel.remainingCapacity > 0) || channelLoading"
        data-test="create-submit"
        @click="submit"
        >{{ t("copyToken.create") }}</el-button
      >
      <el-button v-else type="primary" data-test="result-close" @click="close">{{
        t("common.close")
      }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint,
.capacity-error {
  margin: 0;
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}
.capacity-error {
  color: var(--tcrm-color-danger);
}
.result-token {
  margin: var(--tcrm-space-3) 0;
  padding: var(--tcrm-space-3);
  word-break: break-all;
  background: var(--el-fill-color-light);
  border-radius: var(--tcrm-radius-card);
}
</style>
