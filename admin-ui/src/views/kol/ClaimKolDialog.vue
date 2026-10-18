<script setup lang="ts">
import { ElMessage } from "element-plus";
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { claimKol, findClaimableKol, type ClaimableKolVO } from "@/api/kol/promotion";
import { notifyError } from "@/utils/notify";
import { looksLikeKolCode, validateKolCode, validateKolEmail } from "@/utils/kol-validation";
import { promotionStatusLabelKey, promotionTagType } from "@/utils/promotion";

defineOptions({ name: "ClaimKolDialog" });

const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "claimed"): void;
}>();

const props = defineProps<{ modelValue: boolean }>();

const { t } = useI18n();

const keyword = ref("");
/** The value sent to the API: codes are upper-cased, the backend matches either way. */
const normalizedKeyword = ref("");
const errorText = ref("");
const searching = ref(false);
const claiming = ref(false);
const result = ref<ClaimableKolVO | null>(null);

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    keyword.value = "";
    errorText.value = "";
    result.value = null;
  }
);

/** Exact match on a creator code or an email; the format check picks the right message. */
function validateKeyword(): boolean {
  const text = keyword.value.trim();
  if (!text) {
    errorText.value = t("validation.claimKeywordRequired");
    return false;
  }
  const candidate = looksLikeKolCode(text) ? text.toUpperCase() : text;
  normalizedKeyword.value = candidate;
  const message = looksLikeKolCode(text) ? validateKolCode(candidate) : validateKolEmail(candidate);
  if (message) {
    errorText.value = message;
    return false;
  }
  return true;
}

async function search() {
  if (!validateKeyword()) return;
  searching.value = true;
  errorText.value = "";
  try {
    const found = await findClaimableKol(normalizedKeyword.value);
    if (!found) {
      result.value = null;
      ElMessage.info(t("kol.claimNotFound"));
      return;
    }
    result.value = found;
  } catch (error) {
    notifyError(error);
  } finally {
    searching.value = false;
  }
}

async function claim() {
  const found = result.value;
  if (!found) return;
  claiming.value = true;
  try {
    await claimKol(found.id);
    ElMessage.success(t("kol.claimDone"));
    emit("claimed");
    emit("update:modelValue", false);
  } catch (error) {
    // 409 KOL_ALREADY_CLAIMED arrives with its own mapped text.
    notifyError(error);
  } finally {
    claiming.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('kol.claimTitle')"
    width="min(520px, calc(100vw - 24px))"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      v-if="errorText"
      class="claim-error"
      type="error"
      :closable="false"
      :title="errorText"
      data-test="claim-error"
    />
    <el-form label-position="top" @submit.prevent="search">
      <el-form-item :label="t('kol.claimKeyword')">
        <div class="claim-search">
          <el-input
            v-model="keyword"
            data-test="claim-keyword"
            maxlength="128"
            :placeholder="t('kol.claimPlaceholder')"
            @keyup.enter="search"
          />
          <el-button data-test="claim-search" :loading="searching" @click="search">
            {{ t("kol.claimSearch") }}
          </el-button>
        </div>
      </el-form-item>
    </el-form>

    <el-descriptions v-if="result" :column="1" border data-test="claim-result">
      <el-descriptions-item :label="t('kol.code')">{{ result.kolCode }}</el-descriptions-item>
      <el-descriptions-item :label="t('common.name')">{{ result.name }}</el-descriptions-item>
      <el-descriptions-item :label="t('common.email')">{{
        result.maskedEmail
      }}</el-descriptions-item>
      <el-descriptions-item :label="t('kol.promotionStatus')">
        <el-tag :type="promotionTagType(result.displayStatus)">
          {{ t(promotionStatusLabelKey(result.displayStatus)) }}
        </el-tag>
      </el-descriptions-item>
    </el-descriptions>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button
        v-if="result"
        type="primary"
        data-test="claim-confirm"
        :loading="claiming"
        @click="claim"
      >
        {{ t("kol.claimConfirm") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.claim-error {
  margin-bottom: 12px;
}
.claim-search {
  display: flex;
  gap: 8px;
  width: 100%;
}
.claim-search .el-input {
  flex: 1;
}
</style>
