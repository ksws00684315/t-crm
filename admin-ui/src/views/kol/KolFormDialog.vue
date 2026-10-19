<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { createKol, editKol, reviewKol } from "@/api/kol/kol";
import { listLinkTemplates } from "@/api/link/template";
import type { BdUserVO, KolVO, LinkTemplateVO } from "@/api/types";
import {
  type SocialAccountInput,
  validateCountry,
  validateInviteCode,
  validateKolEmail,
  validateKolName,
  validateRemark,
  validateSheetUrl,
  validateSocialAccount,
  validateWhatsapp,
  withPrimary
} from "@/utils/kol-validation";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";
import { percentToRate, rateToPercent, validatePercent } from "@/utils/rate";
import { rule, serverFieldErrors } from "@/utils/validation";

defineOptions({ name: "KolFormDialog" });

const MAX_SOCIAL_ACCOUNTS = 10;

const { t } = useI18n();

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    /** The KOL being edited; null creates a new one. */
    kol: KolVO | null;
    /** Show the BD picker when creating (super administrator, or kol:edit outside OWN). */
    canPickBd: boolean;
    bdUsers: BdUserVO[];
    /**
     * Review mode (task 25b §3.3): email and creator code are read-only, the footer adds the
     * approve button that saves the profile and then moves the status to PENDING_PROMOTION.
     */
    review?: boolean;
  }>(),
  { review: false }
);
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const editing = computed(() => props.kol !== null);
const title = computed(() => {
  if (props.review && props.kol) return t("kol.reviewTitle", { code: props.kol.kolCode });
  return editing.value
    ? t("kol.editTitle", { code: props.kol?.kolCode ?? "" })
    : t("kol.createTitle");
});
const formRef = ref<FormInstance>();
const saving = ref(false);
const approving = ref(false);
const templatesLoading = ref(false);
const templates = ref<LinkTemplateVO[]>([]);
const serverErrors = ref<Record<string, string>>({});
const socialError = ref("");
const form = reactive({
  name: "",
  email: "",
  country: "",
  bdUserId: null as string | null,
  percent: "",
  remark: "",
  whatsapp: "",
  phone: "",
  linkTemplateId: null as string | null,
  inviteCode: "",
  sheetUrl: "",
  socialAccounts: [] as SocialAccountInput[]
});

const rules: FormRules = {
  name: [rule(validateKolName)],
  email: [rule(validateKolEmail)],
  country: [rule(validateCountry)],
  percent: [rule(validatePercent)],
  remark: [rule(validateRemark)],
  whatsapp: [rule(validateWhatsapp)],
  phone: [rule(validateWhatsapp)],
  inviteCode: [rule(validateInviteCode)],
  sheetUrl: [rule(validateSheetUrl)]
};

const templateOptions = computed(() => {
  const enabled = templates.value.filter(item => item.status === 1);
  const options = enabled.map(item => ({ id: item.id, label: item.templateName }));
  const currentId = form.linkTemplateId;
  if (!currentId || enabled.some(item => item.id === currentId)) return options;
  const known = templates.value.find(item => item.id === currentId);
  const name = known?.templateName || props.kol?.linkTemplateName || currentId;
  return [...options, { id: currentId, label: t("kol.templateDisabled", { name }) }];
});

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
    form.whatsapp = current?.whatsapp ?? "";
    form.phone = current?.phone ?? "";
    form.linkTemplateId = current?.linkTemplateId ?? null;
    form.inviteCode = current?.inviteCode ?? "";
    form.sheetUrl = current?.sheetUrl ?? "";
    form.socialAccounts = (current?.socialAccounts ?? []).map(account => ({
      platform: account.platform,
      handle: account.handle,
      url: account.url ?? "",
      followers: account.followers ?? "",
      primary: account.primary === true
    }));
    formRef.value?.clearValidate();
    void loadTemplates();
  }
);

async function loadTemplates() {
  templatesLoading.value = true;
  try {
    templates.value = await listLinkTemplates();
  } catch (error) {
    templates.value = [];
    notifyError(error);
  } finally {
    templatesLoading.value = false;
  }
}

function addSocialAccount() {
  form.socialAccounts.push({ platform: "", handle: "", url: "", followers: "", primary: false });
}

function removeSocialAccount(index: number) {
  form.socialAccounts.splice(index, 1);
}

function togglePrimary(index: number) {
  const next = withPrimary(form.socialAccounts, index);
  form.socialAccounts.splice(0, form.socialAccounts.length, ...next);
}

function close() {
  emit("update:modelValue", false);
}

function checkSocialAccounts(): boolean {
  const primaryCount = form.socialAccounts.filter(account => account.primary).length;
  if (primaryCount > 1) {
    socialError.value = t("errors.KOL_MULTIPLE_PRIMARY");
    return false;
  }
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
  if (!(await persistProfile())) return;
  ElMessage.success(props.kol ? t("kol.saved") : t("kol.created"));
  emit("saved");
  close();
}

/**
 * Validates and saves the profile; false when validation or the request failed (the field errors
 * and the API failure are already shown to the user).
 */
async function persistProfile(): Promise<boolean> {
  serverErrors.value = {};
  const valid = formRef.value ? await formRef.value.validate().catch(() => false) : false;
  if (!checkSocialAccounts() || !valid) return false;
  saving.value = true;
  try {
    if (props.kol) {
      await editKol(props.kol.id, {
        name: form.name.trim(),
        country: form.country.trim() ? form.country.trim().toUpperCase() : null,
        socialAccounts: form.socialAccounts.map(account => ({
          platform: account.platform.trim(),
          handle: account.handle.trim(),
          url: account.url.trim() || null,
          followers: account.followers.trim() || null,
          primary: account.primary
        })),
        remark: form.remark.trim() || null,
        whatsapp: form.whatsapp.trim() || null,
        phone: form.phone.trim() || null
      });
    } else {
      await createKol({
        name: form.name.trim(),
        email: form.email.trim(),
        country: form.country.trim() ? form.country.trim().toUpperCase() : null,
        socialAccounts: form.socialAccounts.map(account => ({
          platform: account.platform.trim(),
          handle: account.handle.trim(),
          url: account.url.trim() || null,
          followers: account.followers.trim() || null,
          primary: account.primary
        })),
        defaultRate: percentToRate(form.percent),
        remark: form.remark.trim() || null,
        whatsapp: form.whatsapp.trim() || null,
        phone: form.phone.trim() || null,
        linkTemplateId: form.linkTemplateId,
        inviteCode: form.inviteCode.trim() || null,
        sheetUrl: form.sheetUrl.trim() || null,
        bdUserId: props.canPickBd ? form.bdUserId : null
      });
    }
    return true;
  } catch (error) {
    serverErrors.value = serverFieldErrors(error, [
      "name",
      "email",
      "country",
      "bdUserId",
      "defaultRate",
      "remark",
      "whatsapp",
      "phone",
      "linkTemplateId",
      "inviteCode",
      "sheetUrl"
    ]);
    if (serverErrors.value.defaultRate) serverErrors.value.percent = serverErrors.value.defaultRate;
    notifyError(error);
    return false;
  } finally {
    saving.value = false;
  }
}

/** Review and profile update are one operation on the contract endpoint. */
async function approveReview() {
  const kol = props.kol;
  if (!kol) return;
  serverErrors.value = {};
  const valid = formRef.value ? await formRef.value.validate().catch(() => false) : false;
  if (!checkSocialAccounts() || !valid) return;
  approving.value = true;
  try {
    const primary = form.socialAccounts.find(account => account.primary);
    await reviewKol(kol.id, {
      name: form.name.trim(),
      phone: form.phone.trim() || null,
      whatsapp: form.whatsapp.trim() || null,
      primaryPlatform: primary?.platform.trim() || null,
      primaryHandle: primary?.handle.trim() || null,
      country: form.country.trim() ? form.country.trim().toUpperCase() : null,
      remark: form.remark.trim() || null
    });
    ElMessage.success(t("kol.reviewApproved"));
    emit("saved");
    close();
  } catch (error) {
    if (error instanceof ApiError && error.errorCode === "KOL_REVIEW_ALREADY_DONE") {
      ElMessage.info(t("kol.reviewAlreadyDone"));
      emit("saved");
      close();
    } else notifyError(error);
  } finally {
    approving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="860px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" @submit.prevent>
      <el-form-item :label="t('common.name')" prop="name" :error="serverErrors.name">
        <el-input v-model="form.name" maxlength="64" />
      </el-form-item>
      <el-form-item v-if="review" :label="t('kol.code')">
        <el-input :model-value="kol?.kolCode ?? ''" disabled data-test="review-code" />
      </el-form-item>
      <el-form-item :label="t('common.email')" prop="email" :error="serverErrors.email">
        <el-input
          v-model="form.email"
          data-test="email-input"
          maxlength="128"
          :disabled="editing || review"
        />
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
      <el-form-item
        v-if="!editing"
        :label="t('kol.rateLabel')"
        prop="percent"
        :error="serverErrors.percent"
      >
        <el-input
          v-model="form.percent"
          :placeholder="t('kol.ratePlaceholder')"
          style="width: 200px"
        >
          <template #append>%</template>
        </el-input>
      </el-form-item>

      <h4 class="section">{{ t("kol.contactTitle") }}</h4>
      <el-form-item :label="t('kol.whatsapp')" prop="whatsapp" :error="serverErrors.whatsapp">
        <el-input
          v-model="form.whatsapp"
          data-test="whatsapp"
          maxlength="32"
          :placeholder="t('kol.whatsappPlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('kol.phone')" prop="phone" :error="serverErrors.phone">
        <el-input
          v-model="form.phone"
          data-test="phone"
          maxlength="32"
          :placeholder="t('kol.phonePlaceholder')"
        />
      </el-form-item>
      <el-form-item
        v-if="!editing"
        :label="t('kol.agentPlatform')"
        prop="linkTemplateId"
        :error="serverErrors.linkTemplateId"
      >
        <el-select
          v-model="form.linkTemplateId"
          data-test="template-select"
          clearable
          filterable
          :loading="templatesLoading"
          :placeholder="t('kol.templatePlaceholder')"
        >
          <el-option
            v-for="item in templateOptions"
            :key="item.id"
            :label="item.label"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-if="!editing"
        :label="t('kol.inviteCode')"
        prop="inviteCode"
        :error="serverErrors.inviteCode"
      >
        <el-input v-model="form.inviteCode" data-test="invite-code" maxlength="64" />
      </el-form-item>
      <el-form-item
        v-if="!editing"
        :label="t('kol.sheetUrl')"
        prop="sheetUrl"
        :error="serverErrors.sheetUrl"
      >
        <el-input
          v-model="form.sheetUrl"
          data-test="sheet-url"
          maxlength="512"
          :placeholder="t('kol.sheetUrlPlaceholder')"
        />
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
            <el-radio
              data-test="primary"
              :model-value="account.primary"
              :value="true"
              @click.prevent="togglePrimary(index)"
            >
              {{ t("kol.primary") }}
            </el-radio>
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
      <el-button
        v-if="!review"
        type="primary"
        data-test="kol-save"
        :loading="saving"
        @click="submit"
        >{{ t("common.save") }}</el-button
      >
      <el-button
        v-else
        type="success"
        data-test="review-approve"
        :loading="approving"
        @click="approveReview"
        >{{ t("kol.approveReview") }}</el-button
      >
    </template>
  </el-dialog>
</template>

<style scoped>
.section {
  margin: 4px 0 12px;
  font-weight: 600;
}

.social {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.social__row {
  display: grid;
  grid-template-columns: 0.9fr 1fr 1.3fr 0.8fr auto auto;
  gap: 8px;
  align-items: center;
}
</style>
