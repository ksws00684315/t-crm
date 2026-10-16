<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { createKol, editKol } from "@/api/kol/kol";
import type { BdUserVO, KolVO } from "@/api/types";
import {
  type SocialAccountInput,
  validateCountry,
  validateKolEmail,
  validateKolName,
  validateRemark,
  validateSocialAccount
} from "@/utils/kol-validation";
import { notifyError } from "@/utils/notify";
import { percentToRate, rateToPercent, validatePercent } from "@/utils/rate";
import { rule, serverFieldErrors } from "@/utils/validation";

defineOptions({ name: "KolFormDialog" });

const MAX_SOCIAL_ACCOUNTS = 10;

const { t } = useI18n();

const props = defineProps<{
  modelValue: boolean;
  /** The KOL being edited; null creates a new one. */
  kol: KolVO | null;
  /** Show the BD picker when creating (super administrator, or kol:edit outside OWN). */
  canPickBd: boolean;
  bdUsers: BdUserVO[];
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const editing = computed(() => props.kol !== null);
const title = computed(() =>
  editing.value ? t("kol.editTitle", { code: props.kol?.kolCode ?? "" }) : t("kol.createTitle")
);
const formRef = ref<FormInstance>();
const saving = ref(false);
const serverErrors = ref<Record<string, string>>({});
const socialError = ref("");
const form = reactive({
  name: "",
  email: "",
  country: "",
  bdUserId: null as string | null,
  percent: "",
  remark: "",
  socialAccounts: [] as SocialAccountInput[]
});

const rules: FormRules = {
  name: [rule(validateKolName)],
  email: [rule(validateKolEmail)],
  country: [rule(validateCountry)],
  percent: [rule(validatePercent)],
  remark: [rule(validateRemark)]
};

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    serverErrors.value = {};
    socialError.value = "";
    const current = props.kol;
    form.name = current?.name ?? "";
    form.email = current?.email ?? "";
    form.country = current?.country ?? "";
    form.bdUserId = null;
    form.percent = rateToPercent(current?.defaultRate);
    form.remark = current?.remark ?? "";
    form.socialAccounts = (current?.socialAccounts ?? []).map(account => ({
      platform: account.platform,
      handle: account.handle,
      url: account.url ?? "",
      followers: account.followers ?? ""
    }));
    formRef.value?.clearValidate();
  }
);

function addSocialAccount() {
  form.socialAccounts.push({ platform: "", handle: "", url: "", followers: "" });
}

function removeSocialAccount(index: number) {
  form.socialAccounts.splice(index, 1);
}

function close() {
  emit("update:modelValue", false);
}

function checkSocialAccounts(): boolean {
  for (const [index, account] of form.socialAccounts.entries()) {
    const message = validateSocialAccount(account);
    if (message) {
      socialError.value = t("kol.socialError", { index: index + 1, message });
      return false;
    }
  }
  socialError.value = "";
  return true;
}

async function submit() {
  serverErrors.value = {};
  const valid = formRef.value ? await formRef.value.validate().catch(() => false) : false;
  if (!checkSocialAccounts() || !valid) return;
  saving.value = true;
  const body = {
    name: form.name.trim(),
    email: form.email.trim(),
    country: form.country.trim().toUpperCase(),
    socialAccounts: form.socialAccounts.map(account => ({
      platform: account.platform.trim(),
      handle: account.handle.trim(),
      url: account.url.trim() || null,
      followers: account.followers.trim() || null
    })),
    defaultRate: percentToRate(form.percent),
    remark: form.remark.trim() || null
  };
  try {
    if (props.kol) {
      await editKol(props.kol.id, body);
    } else {
      await createKol({ ...body, bdUserId: props.canPickBd ? form.bdUserId : null });
    }
    ElMessage.success(props.kol ? t("kol.saved") : t("kol.created"));
    emit("saved");
    close();
  } catch (error) {
    serverErrors.value = serverFieldErrors(error, [
      "name",
      "email",
      "country",
      "bdUserId",
      "defaultRate",
      "remark"
    ]);
    if (serverErrors.value.defaultRate) serverErrors.value.percent = serverErrors.value.defaultRate;
    notifyError(error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="720px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" @submit.prevent>
      <el-form-item :label="t('common.name')" prop="name" :error="serverErrors.name">
        <el-input v-model="form.name" maxlength="64" />
      </el-form-item>
      <el-form-item :label="t('common.email')" prop="email" :error="serverErrors.email">
        <el-input v-model="form.email" maxlength="128" />
      </el-form-item>
      <el-form-item :label="t('common.country')" prop="country" :error="serverErrors.country">
        <el-input
          v-model="form.country"
          maxlength="2"
          :placeholder="t('kol.countryCodePlaceholder')"
        />
      </el-form-item>
      <el-form-item
        v-if="!editing && canPickBd"
        data-test="assign-bd"
        :label="t('kol.bd')"
        prop="bdUserId"
        :error="serverErrors.bdUserId"
      >
        <el-select
          v-model="form.bdUserId"
          clearable
          filterable
          :placeholder="t('common.unassigned')"
        >
          <el-option
            v-for="bd in bdUsers"
            :key="bd.id"
            :label="t('kol.bdOption', { nickname: bd.nickname, username: bd.username })"
            :value="bd.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('kol.rateLabel')" prop="percent" :error="serverErrors.percent">
        <el-input
          v-model="form.percent"
          :placeholder="t('kol.ratePlaceholder')"
          style="width: 200px"
        >
          <template #append>%</template>
        </el-input>
      </el-form-item>
      <el-form-item :label="t('kol.social')" :error="socialError">
        <div class="social">
          <div v-for="(account, index) in form.socialAccounts" :key="index" class="social__row">
            <el-input
              v-model="account.platform"
              :placeholder="t('kol.platformPlaceholder')"
              maxlength="32"
            />
            <el-input
              v-model="account.handle"
              :placeholder="t('kol.handlePlaceholder')"
              maxlength="128"
            />
            <el-input
              v-model="account.url"
              :placeholder="t('kol.urlPlaceholder')"
              maxlength="512"
            />
            <el-input
              v-model="account.followers"
              :placeholder="t('kol.followersPlaceholder')"
              maxlength="15"
            />
            <el-button link type="danger" @click="removeSocialAccount(index)">{{
              t("common.delete")
            }}</el-button>
          </div>
          <el-button
            :disabled="form.socialAccounts.length >= MAX_SOCIAL_ACCOUNTS"
            @click="addSocialAccount"
          >
            {{ t("kol.addSocial", { max: MAX_SOCIAL_ACCOUNTS }) }}
          </el-button>
        </div>
      </el-form-item>
      <el-form-item :label="t('common.remark')" prop="remark" :error="serverErrors.remark">
        <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ t("common.save") }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.social {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.social__row {
  display: grid;
  grid-template-columns: 1fr 1.2fr 1.6fr 1fr auto;
  gap: 8px;
}
</style>
