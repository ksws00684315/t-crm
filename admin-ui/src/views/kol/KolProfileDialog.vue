<script setup lang="ts">
import CopyText from "@/components/CopyText.vue";
import PhoneNumberInput from "@/components/PhoneNumberInput.vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, nextTick, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { editKol, resetKolPassword, reviewKol } from "@/api/kol/kol";
import type { KolVO } from "@/api/types";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import { validateKolName, validateWhatsapp } from "@/utils/kol-validation";
import { notifyError } from "@/utils/notify";
import { accessOf } from "@/utils/permission";
import { rule } from "@/utils/validation";

defineOptions({ name: "KolProfileDialog" });

/**
 * Profile edit and review share one field set (task 32 §2.2, after 04.02): editable name,
 * WhatsApp, phone, main platform and handle; email and creator ID are read-only. Review mode
 * saves through the review endpoint, so the first save flips the status to approved.
 *
 * Task 156: the dialog opens as a read-only detail view once the profile has been filled in —
 * Edit switches the three groups to the form. The inviter comes from the current BD and stays
 * read-only. Business Discord and WhatsApp accounts are recorded per creator and can only be
 * changed through the ordinary edit endpoint.
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
const me = useUserStore();

const formRef = ref<FormInstance>();
const saving = ref(false);
/** False = read-only view; the Edit button flips it for this opening only. */
const editing = ref(false);
const form = reactive({
  name: "",
  whatsapp: "",
  phone: "",
  platform: "",
  handle: "",
  bdDiscord: "",
  bdWhatsapp: ""
});

const profileEditable = computed(() => Boolean(props.review) || editing.value);
const inviterEditable = computed(() => !props.review && editing.value);
const title = computed(() =>
  props.review
    ? t("kol.reviewTitle", { code: props.kol?.kolCode ?? "" })
    : t(editing.value ? "kol.editProfileTitle" : "kol.actionEdit")
);

/** One-time plaintext of the last super-administrator reset; cleared whenever the dialog opens. */
const resetResult = ref<string | null>(null);
const resetResultRef = ref<HTMLElement>();
const resetting = ref(false);

/**
 * Super administrator only (task 147); review mode stays on its approve action, and an account
 * still waiting for its invitation has no password to reset.
 */
const canResetPassword = computed(
  () => !props.review && accessOf(me.user).isSuperAdmin && props.kol?.accountStatus === 1
);

const rules: FormRules = {
  name: [rule(validateKolName)],
  whatsapp: [rule(validateWhatsapp)],
  phone: [rule(validateWhatsapp)]
};

function primaryAccount(kol: KolVO | null) {
  return kol?.socialAccounts.find(account => account.primary) ?? null;
}

function text(value: string): string {
  return value.trim() ? value : "—";
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
    form.bdDiscord = kol?.bdDiscord ?? "";
    form.bdWhatsapp = kol?.bdWhatsapp ?? "";
    editing.value = Boolean(props.review);
    resetResult.value = null;
    formRef.value?.clearValidate();
  }
);

function close() {
  emit("update:modelValue", false);
}

/**
 * Super-administrator password reset (task 147): a second confirmation first, then the backend
 * replaces the password with a generated one. The plaintext comes back once and is shown here
 * until the dialog closes; nothing else (no mail, no log) carries it.
 */
async function resetPassword() {
  const kol = props.kol;
  if (!kol || resetting.value) return;
  try {
    await ElMessageBox.confirm(
      t("kol.confirmResetPassword", { code: kol.kolCode, name: kol.name }),
      t("kol.resetPasswordTitle"),
      { type: "warning" }
    );
  } catch {
    return; // cancelled
  }
  resetting.value = true;
  try {
    const result = await resetKolPassword(kol.id);
    resetResult.value = result.password;
    ElMessage.success(t("kol.resetPasswordDone"));
    await nextTick();
    resetResultRef.value?.focus();
  } catch (error) {
    notifyError(error);
  } finally {
    resetting.value = false;
  }
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
        phone: form.phone.trim() || null,
        bdDiscord: form.bdDiscord.trim() || null,
        bdWhatsapp: form.bdWhatsapp.trim() || null
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
    class="kol-profile-dialog"
    width="min(640px, calc(100vw - 32px))"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div
      v-if="resetResult"
      ref="resetResultRef"
      class="reset-result"
      tabindex="-1"
      data-test="reset-password-result"
    >
      <p class="reset-hint">{{ t("kol.resetPasswordResultHint") }}</p>
      <CopyText :value="resetResult" hide-tooltip />
    </div>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      :data-test="profileEditable ? undefined : 'profile-view'"
      :class="{ 'profile-layout--view': !profileEditable }"
      class="profile-layout"
      label-position="top"
      scroll-to-error
      @submit.prevent
    >
      <section class="profile-section profile-section--identity">
        <h2 class="profile-section__title">{{ t("kol.tabProfile") }}</h2>
        <el-form-item :label="t('kol.kolName')" prop="name">
          <el-input
            v-if="profileEditable"
            v-model="form.name"
            data-test="profile-name"
            maxlength="64"
          />
          <span v-else class="profile-value" data-test="profile-view-name">{{
            text(form.name)
          }}</span>
        </el-form-item>
        <el-form-item :label="t('order.kolCode')">
          <span class="profile-value" data-test="profile-code">{{ kol?.kolCode || "—" }}</span>
        </el-form-item>
      </section>

      <section class="profile-section profile-section--contact">
        <h2 class="profile-section__title">{{ t("kol.profileContact") }}</h2>
        <el-form-item :label="t('common.email')">
          <div class="profile-value" data-test="profile-email">
            <CopyText v-if="kol?.email" :value="kol.email" hide-tooltip />
            <span v-else>—</span>
          </div>
        </el-form-item>
        <el-form-item :label="t('kol.whatsapp')" prop="whatsapp">
          <PhoneNumberInput
            v-if="profileEditable"
            v-model="form.whatsapp"
            data-test="profile-whatsapp"
            :placeholder="t('kol.whatsappPlaceholder')"
          />
          <div v-else class="profile-value">
            <CopyText v-if="form.whatsapp" :value="form.whatsapp" hide-tooltip />
            <span v-else>—</span>
          </div>
        </el-form-item>
        <el-form-item :label="t('kol.phone')" prop="phone">
          <PhoneNumberInput
            v-if="profileEditable"
            v-model="form.phone"
            data-test="profile-phone"
            :placeholder="t('kol.phonePlaceholder')"
          />
          <div v-else class="profile-value">
            <CopyText v-if="form.phone" :value="form.phone" hide-tooltip />
            <span v-else>—</span>
          </div>
        </el-form-item>
        <el-form-item :label="t('kol.platformLabel')">
          <el-input
            v-if="profileEditable"
            v-model="form.platform"
            data-test="profile-platform"
            maxlength="32"
            :placeholder="t('kol.platformPlaceholder')"
          />
          <span v-else class="profile-value">{{ text(form.platform) }}</span>
        </el-form-item>
        <el-form-item :label="t('kol.handleLabel')">
          <el-input
            v-if="profileEditable"
            v-model="form.handle"
            data-test="profile-handle"
            maxlength="128"
            :placeholder="t('kol.handlePlaceholder')"
          />
          <span v-else class="profile-value">{{ text(form.handle) }}</span>
        </el-form-item>
      </section>

      <section
        class="profile-section profile-section--inviter"
        :data-test="inviterEditable ? undefined : 'inviter-view'"
      >
        <h2 class="profile-section__title">{{ t("kol.tabInviter") }}</h2>
        <el-form-item :label="t('kol.inviter')">
          <div class="profile-value">
            <div data-test="profile-inviter">{{ kol?.bdUserName || "—" }}</div>
            <p v-if="inviterEditable" class="form-hint">{{ t("kol.inviterHint") }}</p>
          </div>
        </el-form-item>
        <p v-if="inviterEditable" class="form-hint accounts-hint">
          {{ t("kol.inviterAccountsHint") }}
        </p>
        <el-form-item :label="t('kol.bdDiscord')">
          <el-input
            v-if="inviterEditable"
            v-model="form.bdDiscord"
            data-test="profile-bd-discord"
            maxlength="64"
            :placeholder="t('kol.bdDiscordPlaceholder')"
          />
          <div v-else class="profile-value" data-test="profile-bd-discord-view">
            <CopyText v-if="form.bdDiscord" :value="form.bdDiscord" hide-tooltip />
            <span v-else>—</span>
          </div>
        </el-form-item>
        <el-form-item :label="t('kol.bdWhatsapp')">
          <el-input
            v-if="inviterEditable"
            v-model="form.bdWhatsapp"
            data-test="profile-bd-whatsapp"
            maxlength="64"
            :placeholder="t('kol.bdWhatsappPlaceholder')"
          />
          <div v-else class="profile-value" data-test="profile-bd-whatsapp-view">
            <CopyText v-if="form.bdWhatsapp" :value="form.bdWhatsapp" hide-tooltip />
            <span v-else>—</span>
          </div>
        </el-form-item>
      </section>
    </el-form>

    <template #footer>
      <div class="profile-actions">
        <el-button
          v-if="canResetPassword"
          type="danger"
          link
          data-test="reset-password"
          :loading="resetting"
          @click="resetPassword"
        >
          {{ t("kol.resetPassword") }}
        </el-button>
        <div class="profile-actions__main">
          <el-button @click="close">{{
            profileEditable ? t("common.cancel") : t("common.close")
          }}</el-button>
          <el-button
            v-if="!profileEditable"
            type="primary"
            data-test="profile-edit"
            @click="editing = true"
          >
            {{ t("kol.editProfile") }}
          </el-button>
          <el-button
            v-else
            type="primary"
            data-test="profile-save"
            :loading="saving"
            @click="submit"
          >
            {{ review ? t("kol.approveReview") : t("common.save") }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.profile-layout {
  display: grid;
  gap: var(--tcrm-space-4);
  align-items: start;
}

.profile-section {
  min-width: 0;
  padding: var(--tcrm-space-3);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}

.profile-section__title {
  margin: 0 0 var(--tcrm-space-3);
  color: var(--tcrm-color-text);
  font-size: var(--tcrm-font-size-section);
  line-height: var(--tcrm-line-height-section);
  font-weight: 600;
}

.profile-section :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

.profile-layout--view :deep(.el-form-item) {
  margin-bottom: var(--tcrm-space-2);
}

.profile-layout--view :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

.profile-layout--view :deep(.el-form-item__label) {
  margin-bottom: var(--tcrm-space-1);
  color: var(--tcrm-color-text-secondary);
}

.profile-layout--view :deep(.el-form-item__content) {
  line-height: var(--tcrm-line-height-body);
}

.profile-value {
  min-width: 0;
  color: var(--tcrm-color-text);
  overflow-wrap: anywhere;
}

.profile-value :deep(.copy-text__value) {
  white-space: normal;
  overflow-wrap: anywhere;
}

@media (min-width: 601px) {
  .profile-layout--view {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: auto 1fr;
    grid-template-areas: "identity contact" "inviter contact";
  }

  .profile-layout--view .profile-section--identity {
    grid-area: identity;
  }

  .profile-layout--view .profile-section--contact {
    grid-area: contact;
    align-self: stretch;
  }

  .profile-layout--view .profile-section--inviter {
    grid-area: inviter;
  }
}

.form-hint,
.reset-hint {
  margin: var(--tcrm-space-1) 0 0;
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
}

.accounts-hint {
  margin: 0 0 var(--tcrm-space-3);
}

.reset-result {
  margin-bottom: var(--tcrm-space-3);
  padding: var(--tcrm-space-3) var(--tcrm-space-4);
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: var(--tcrm-radius-control);
  background: var(--tcrm-color-warning-bg);
}

.reset-hint {
  margin: 0 0 var(--tcrm-space-2);
}

.profile-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2) var(--tcrm-space-4);
  padding-top: var(--tcrm-space-3);
  border-top: 1px solid var(--tcrm-color-border);
}

.profile-actions__main {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
  margin-inline-start: auto;
}

.profile-actions__main :deep(.el-button + .el-button) {
  margin: 0;
}
</style>
