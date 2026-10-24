<script setup lang="ts">
import NameCode from "@/components/NameCode.vue";
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { editKol, reviewKol } from "@/api/kol/kol";
import type { KolVO } from "@/api/types";
import { ApiError } from "@/utils/http/error";
import { validateKolName, validateWhatsapp } from "@/utils/kol-validation";
import { notifyError } from "@/utils/notify";
import { rule } from "@/utils/validation";

defineOptions({ name: "KolProfileDialog" });

/**
 * Profile edit and review share one field set (task 32 §2.2, after 04.02): editable name,
 * WhatsApp, phone, main platform and handle; email and creator ID are read-only. Review mode
 * saves through the review endpoint, so the first save flips the status to approved.
 */
const props = defineProps<{
  modelValue: boolean;
  kol: KolVO | null;
  /** Review mode: saves via `POST /review` and approves the creator. */
  review?: boolean;
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const { t } = useI18n();

const title = computed(() => {
  const kol = props.kol;
  if (!kol) return t("kol.editTitle", { code: "" });
  if (props.review) return t("kol.reviewTitle", { code: kol.kolCode });
  return t("kol.editProfileTitle", { code: kol.kolCode, name: kol.name });
});

const formRef = ref<FormInstance>();
const saving = ref(false);
const form = reactive({
  name: "",
  whatsapp: "",
  phone: "",
  platform: "",
  handle: ""
});

const rules: FormRules = {
  name: [rule(validateKolName)],
  whatsapp: [rule(validateWhatsapp)],
  phone: [rule(validateWhatsapp)]
};

function primaryAccount(kol: KolVO | null) {
  return kol?.socialAccounts.find(account => account.primary) ?? null;
}

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    const kol = props.kol;
    const primary = primaryAccount(kol);
    form.name = kol?.name ?? "";
    form.whatsapp = kol?.whatsapp ?? "";
    form.phone = kol?.phone ?? "";
    form.platform = primary?.platform ?? "";
    form.handle = primary?.handle ?? "";
    formRef.value?.clearValidate();
  }
);

function close() {
  emit("update:modelValue", false);
}

/**
 * Keeps every stored social account and only rewrites the primary one, so the ordinary edit
 * endpoint (`PUT /kol/kols/{id}`) carries fields this dialog does not show unchanged.
 */
function socialAccountsForEdit() {
  const kol = props.kol;
  if (!kol) return [];
  const rest = kol.socialAccounts
    .filter(account => !account.primary)
    .map(account => ({
      platform: account.platform,
      handle: account.handle,
      url: account.url,
      followers: account.followers,
      primary: false
    }));
  if (!form.platform.trim() && !form.handle.trim()) return rest;
  return [
    {
      platform: form.platform.trim(),
      handle: form.handle.trim(),
      url: primaryAccount(kol)?.url ?? null,
      followers: primaryAccount(kol)?.followers ?? null,
      primary: true
    },
    ...rest
  ];
}

async function submit() {
  const kol = props.kol;
  if (!kol || saving.value) return;
  const valid = formRef.value ? await formRef.value.validate().catch(() => false) : false;
  if (!valid) return;
  saving.value = true;
  try {
    if (props.review) {
      await reviewKol(kol.id, {
        name: form.name.trim(),
        phone: form.phone.trim() || null,
        whatsapp: form.whatsapp.trim() || null,
        primaryPlatform: form.platform.trim() || null,
        primaryHandle: form.handle.trim() || null,
        country: kol.country,
        remark: kol.remark
      });
      ElMessage.success(t("kol.reviewApproved"));
    } else {
      await editKol(kol.id, {
        name: form.name.trim(),
        country: kol.country,
        socialAccounts: socialAccountsForEdit(),
        remark: kol.remark,
        whatsapp: form.whatsapp.trim() || null,
        phone: form.phone.trim() || null
      });
      ElMessage.success(t("kol.saved"));
    }
    emit("saved");
    close();
  } catch (error) {
    if (
      props.review &&
      error instanceof ApiError &&
      error.errorCode === "KOL_REVIEW_ALREADY_DONE"
    ) {
      ElMessage.info(t("kol.reviewAlreadyDone"));
      emit("saved");
      close();
    } else {
      notifyError(error);
    }
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="560px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <NameCode v-if="kol" :name="kol?.name" :code="kol?.kolCode" />
    <el-form ref="formRef" :model="form" :rules="rules" label-width="130px" @submit.prevent>
      <el-form-item :label="t('kol.code')">
        <el-input :model-value="kol?.kolCode ?? ''" disabled data-test="profile-code" />
      </el-form-item>
      <el-form-item :label="t('common.email')">
        <el-input :model-value="kol?.email ?? ''" disabled data-test="profile-email" />
      </el-form-item>
      <el-form-item :label="t('kol.kolName')" prop="name">
        <el-input v-model="form.name" data-test="profile-name" maxlength="64" />
      </el-form-item>
      <el-form-item :label="t('kol.whatsapp')" prop="whatsapp">
        <el-input
          v-model="form.whatsapp"
          data-test="profile-whatsapp"
          maxlength="32"
          :placeholder="t('kol.whatsappPlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('kol.phone')" prop="phone">
        <el-input
          v-model="form.phone"
          data-test="profile-phone"
          maxlength="32"
          :placeholder="t('kol.phonePlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('kol.platformLabel')">
        <el-input
          v-model="form.platform"
          data-test="profile-platform"
          maxlength="32"
          :placeholder="t('kol.platformPlaceholder')"
        />
      </el-form-item>
      <el-form-item :label="t('kol.handleLabel')">
        <el-input
          v-model="form.handle"
          data-test="profile-handle"
          maxlength="128"
          :placeholder="t('kol.handlePlaceholder')"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" data-test="profile-save" :loading="saving" @click="submit">{{
        props.review ? t("kol.approveReview") : t("common.save")
      }}</el-button>
    </template>
  </el-dialog>
</template>
