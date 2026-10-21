<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { createCopyToken } from "@/api/kol/copy-token";
import { listKols } from "@/api/kol/kol";
import { listShops } from "@/api/product/shop";
import type { CreatedCopyTokenVO, KolVO, ShopVO } from "@/api/types";
import { copyText, truncateCopy } from "@/utils/copy";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "CopyTokenCreateDialog" });

const props = defineProps<{
  modelValue: boolean;
  /** When set, the KOL field is prefilled and locked. */
  presetKolId?: string | null;
  presetKolLabel?: string | null;
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
const shops = ref<ShopVO[]>([]);
const defaultCopyShop = computed(() => shops.value.find(s => s.copyTarget) ?? null);

const form = reactive<{
  kolId: string | null;
  quota: number | null;
  validMinutes: number | null;
  targetShopId: string | null;
}>({
  kolId: null,
  quota: null,
  validMinutes: null,
  targetShopId: null
});

const open = computed({
  get: () => props.modelValue,
  set: value => emit("update:modelValue", value)
});

watch(
  () => props.modelValue,
  async openNow => {
    if (!openNow) return;
    result.value = null;
    form.kolId = props.presetKolId ?? null;
    form.quota = null;
    form.validMinutes = null;
    form.targetShopId = null;
    kolOptions.value = [];
    if (props.presetKolId && props.presetKolLabel) {
      kolOptions.value = [
        {
          id: props.presetKolId,
          kolCode: props.presetKolLabel,
          name: props.presetKolLabel,
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
    try {
      shops.value = (await listShops()).filter(s => s.status === 1);
    } catch (error) {
      notifyError(error);
    }
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
  return `${kol.name} (${kol.kolCode})`;
}

async function submit() {
  if (!form.kolId) {
    ElMessage.error(t("copyToken.kolPlaceholder"));
    return;
  }
  saving.value = true;
  try {
    result.value = await createCopyToken({
      kolId: form.kolId,
      quota: form.quota ?? undefined,
      validMinutes: form.validMinutes ?? undefined,
      targetShopId: form.targetShopId
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
    width="520px"
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
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('copyToken.quota')">
          <el-input-number
            v-model="form.quota"
            :min="1"
            data-test="create-quota"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="t('copyToken.validMinutes')">
          <el-input-number
            v-model="form.validMinutes"
            :min="5"
            data-test="create-minutes"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="t('copyToken.shop')">
          <el-select
            v-model="form.targetShopId"
            clearable
            :placeholder="t('copyToken.shopAuto')"
            data-test="create-shop"
            style="width: 100%"
          >
            <el-option
              :label="
                defaultCopyShop
                  ? t('copyToken.shopAutoNamed', { name: defaultCopyShop.shopName })
                  : t('copyToken.shopAuto')
              "
              :value="null as unknown as string"
            />
            <el-option
              v-for="shop in shops"
              :key="shop.id"
              :label="shop.shopName"
              :value="shop.id"
            />
          </el-select>
        </el-form-item>
        <p v-if="!defaultCopyShop" class="hint" data-test="no-default-copy-shop">
          {{ t("copyToken.shopAutoMissing") }}
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
        data-test="create-submit"
        @click="submit"
      >
        {{ t("copyToken.create") }}
      </el-button>
      <el-button v-else type="primary" data-test="result-close" @click="close">{{
        t("common.close")
      }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
}
.result-token {
  margin: 12px 0;
  padding: 12px;
  word-break: break-all;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}
</style>
