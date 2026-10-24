<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { previewRegister, registerKol } from "@/api/register";
import AuthShell from "@/components/AuthShell.vue";
import { useSessionStore } from "@/stores/session";
import { validEmail, validPassword } from "@/utils/auth";
import { ApiError } from "@/utils/http/error";
import { useSubmission } from "@/utils/useSubmission";

defineOptions({ name: "RegisterPage" });

/**
 * Registration form after 02.01 (task 32 §2.5): invitation code on top, then the required
 * account name / email / password / confirm password, then the optional phone, WhatsApp, main
 * platform and social account. No e-mail verification step and no country field. Through a
 * link the code is pre-filled and read-only; from the login page it is empty and required.
 */
const platforms = ["TikTok", "Instagram", "YouTube", "X", "Facebook", "Other"] as const;
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const linkedCode = computed(() => (typeof route.query.code === "string" ? route.query.code : ""));
const code = ref(linkedCode.value.toUpperCase());
const bdUserName = ref("");
const tenantName = ref("");
const linkState = ref<"checking" | "ready" | "invalid" | "empty">(
  linkedCode.value ? "checking" : "empty"
);
const previewError = ref("");
const name = ref("");
const email = ref("");
const password = ref("");
const confirm = ref("");
const whatsapp = ref("");
const phone = ref("");
const platform = ref("");
const handle = ref("");
const emailExists = ref(false);
const { busy, validation, errorText, submit } = useSubmission();
const rateLimitError = ref("");

function blank(value: string): string | null {
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

async function loadPreview() {
  emailExists.value = false;
  previewError.value = "";
  code.value = code.value.toUpperCase();
  if (!code.value) {
    linkState.value = "empty";
    bdUserName.value = "";
    tenantName.value = "";
    return;
  }
  if (!/^[A-HJ-KM-NP-Z2-9]{8}$/.test(code.value)) {
    linkState.value = "invalid";
    return;
  }
  linkState.value = "checking";
  try {
    const preview = await previewRegister(code.value);
    bdUserName.value = preview.bdUserName ?? "";
    tenantName.value = preview.tenantName ?? "";
    linkState.value = "ready";
  } catch (error) {
    linkState.value = "invalid";
    if (error instanceof ApiError && error.status === 429)
      previewError.value = t("register.tryLater");
  }
}

/** 02.01: the code belongs to the super administrator when no BD name comes back. */
const invitedByText = computed(() =>
  bdUserName.value
    ? t("register.invitedBy", { name: bdUserName.value })
    : tenantName.value
      ? t("register.invitedBy", { name: tenantName.value })
      : ""
);

function register() {
  return submit(async () => {
    emailExists.value = false;
    const address = email.value.trim();
    if (!/^[A-HJ-KM-NP-Z2-9]{8}$/.test(code.value)) {
      validation.value = "register.codeInvalid";
      return;
    }
    if (linkState.value !== "ready") {
      validation.value = "register.codeInvalid";
      return;
    }
    if (!name.value.trim() || name.value.trim().length > 64) {
      validation.value = "register.nameRequired";
      return;
    }
    if (!validEmail(address)) {
      validation.value = "auth.emailInvalid";
      return;
    }
    if (!validPassword(password.value)) {
      validation.value = "auth.passwordRule";
      return;
    }
    if (password.value !== confirm.value) {
      validation.value = "auth.passwordMismatch";
      return;
    }
    if (whatsapp.value.trim() && !/^\+[0-9]{8,15}$/.test(whatsapp.value.trim())) {
      validation.value = "register.whatsappInvalid";
      return;
    }
    if (phone.value.trim() && !/^\+[0-9]{8,15}$/.test(phone.value.trim())) {
      validation.value = "register.phoneInvalid";
      return;
    }
    if (!!platform.value !== !!handle.value.trim()) {
      validation.value = "register.socialPairRequired";
      return;
    }
    try {
      const result = await registerKol({
        code: code.value,
        email: address,
        password: password.value,
        name: name.value.trim(),
        phone: blank(phone.value),
        whatsapp: blank(whatsapp.value),
        primaryPlatform: blank(platform.value),
        primaryHandle: blank(handle.value)
      });
      session.acceptSession(result);
      await router.replace("/");
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === "KOL_EMAIL_EXISTS") {
        emailExists.value = true;
        return;
      }
      if (error instanceof ApiError && error.status === 429) {
        rateLimitError.value = t("register.tryLater");
        return;
      }
      throw error;
    }
  });
}

watch(
  linkedCode,
  value => {
    code.value = value.toUpperCase();
    void loadPreview();
  },
  { immediate: true }
);
watch(code, () => {
  if (!linkedCode.value) void loadPreview();
});
</script>

<template>
  <AuthShell :title="t('register.title')">
    <p class="register-subtitle">{{ t("register.subtitle") }}</p>
    <p v-if="linkedCode && linkState === 'invalid' && !previewError" class="register-invalid">
      {{ t("register.invalidLink") }}
    </p>
    <template v-else-if="!linkedCode || linkState === 'ready' || !!previewError">
      <p v-if="linkState === 'ready' && invitedByText" class="register-join">
        {{ invitedByText }}
      </p>
      <p v-if="!linkedCode && linkState === 'invalid'" class="register-invalid">
        {{ t("register.invalidCode") }}
      </p>
      <p v-if="previewError" class="register-invalid">{{ previewError }}</p>
      <el-alert
        v-if="errorText || rateLimitError"
        role="alert"
        type="error"
        :closable="false"
        :title="rateLimitError || errorText"
      />
      <el-alert v-if="emailExists" type="warning" :closable="false">
        {{ t("register.emailExists") }}
        <RouterLink class="register-login" :to="{ path: '/login', query: { email: email.trim() } }">
          {{ t("register.goToLogin") }}
        </RouterLink>
      </el-alert>
      <el-form label-position="top" novalidate @submit.prevent="register">
        <el-form-item :label="t('register.inviteCode')" for="register-code">
          <el-input
            id="register-code"
            v-model="code"
            maxlength="8"
            :readonly="!!linkedCode"
            :disabled="busy || !!linkedCode"
            autocomplete="off"
            :placeholder="linkedCode ? '' : t('register.inviteCodeRequired')"
          />
          <div v-if="linkedCode" class="register-code-note">
            {{ t("register.inviteCodeReadonly") }}
          </div>
        </el-form-item>
        <el-form-item :label="t('register.name')" for="register-name">
          <el-input id="register-name" v-model="name" maxlength="64" :disabled="busy" />
        </el-form-item>
        <el-form-item :label="t('auth.email')" for="register-email">
          <el-input
            id="register-email"
            v-model="email"
            type="email"
            autocomplete="email"
            :disabled="busy"
          />
        </el-form-item>
        <el-form-item :label="t('auth.newPassword')" for="register-password">
          <el-input
            id="register-password"
            v-model="password"
            type="password"
            autocomplete="new-password"
            show-password
            :disabled="busy"
          />
        </el-form-item>
        <el-form-item :label="t('auth.confirmPassword')" for="register-confirm">
          <el-input
            id="register-confirm"
            v-model="confirm"
            type="password"
            autocomplete="new-password"
            show-password
            :disabled="busy"
          />
        </el-form-item>
        <el-form-item :label="t('register.phone')" for="register-phone">
          <el-input id="register-phone" v-model="phone" autocomplete="tel" :disabled="busy" />
        </el-form-item>
        <el-form-item :label="t('register.whatsapp')" for="register-whatsapp">
          <el-input
            id="register-whatsapp"
            v-model="whatsapp"
            autocomplete="tel"
            placeholder="+15551234567"
            :disabled="busy"
          />
        </el-form-item>
        <el-form-item :label="t('register.platformLabel')">
          <el-select
            v-model="platform"
            class="register-platform"
            :placeholder="t('register.platformPlaceholder')"
            :disabled="busy"
          >
            <el-option
              v-for="item in platforms"
              :key="item"
              :label="t(`register.platforms.${item}`)"
              :value="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('register.handle')" for="register-handle">
          <el-input id="register-handle" v-model="handle" maxlength="64" :disabled="busy" />
        </el-form-item>
        <el-button
          native-type="submit"
          type="primary"
          class="auth-submit"
          :loading="busy"
          :disabled="busy"
          >{{ t("register.submit") }}</el-button
        >
      </el-form>
    </template>
    <div class="auth-links">
      <RouterLink to="/login">{{ t("auth.backToLogin") }}</RouterLink>
    </div>
  </AuthShell>
</template>

<style scoped>
.register-subtitle {
  margin: calc(var(--tcrm-space-2) * -1) 0 var(--tcrm-space-4);
  text-align: center;
  color: var(--el-text-color-secondary);
}
.register-join,
.register-invalid {
  margin: calc(var(--tcrm-space-2) * -1) 0 var(--tcrm-space-4);
  text-align: center;
}
.register-code-note {
  width: 100%;
  margin-top: var(--tcrm-space-1);
  font-size: var(--tcrm-font-size-help);
  color: var(--el-text-color-secondary);
}
.register-platform {
  width: 100%;
}
</style>
