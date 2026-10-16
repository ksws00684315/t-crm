<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { previewRegister, registerKol, sendRegisterCode } from "@/api/register";
import AuthShell from "@/components/AuthShell.vue";
import { useSessionStore } from "@/stores/session";
import { validEmail, validPassword } from "@/utils/auth";
import { ApiError } from "@/utils/http/error";
import { useSubmission } from "@/utils/useSubmission";

defineOptions({ name: "RegisterPage" });

const platforms = ["TikTok", "Instagram", "YouTube", "X", "Facebook", "Other"] as const;
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const code = computed(() => (typeof route.query.c === "string" ? route.query.c : ""));
const tenantName = ref("");
const linkState = ref<"checking" | "ready" | "invalid">("checking");
const email = ref("");
const emailCode = ref("");
const password = ref("");
const confirm = ref("");
const whatsapp = ref("");
const platform = ref("");
const handle = ref("");
const profileUrl = ref("");
const name = ref("");
const country = ref("");
const seconds = ref(0);
const emailExists = ref(false);
const { busy, validation, errorText, submit } = useSubmission();
let timer: ReturnType<typeof setInterval> | undefined;

function blank(value: string): string | null {
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

async function loadPreview() {
  emailExists.value = false;
  if (!/^[0-9A-Za-z]{32}$/.test(code.value)) {
    linkState.value = "invalid";
    return;
  }
  linkState.value = "checking";
  try {
    tenantName.value = (await previewRegister(code.value)).tenantName;
    linkState.value = "ready";
  } catch {
    linkState.value = "invalid";
  }
}

function startCountdown() {
  seconds.value = 60;
  if (timer) clearInterval(timer);
  timer = setInterval(() => {
    seconds.value -= 1;
    if (seconds.value <= 0 && timer) clearInterval(timer);
  }, 1000);
}

function sendCode() {
  return submit(async () => {
    emailExists.value = false;
    if (!validEmail(email.value.trim())) {
      validation.value = "auth.emailInvalid";
      return;
    }
    try {
      await sendRegisterCode({ code: code.value, email: email.value.trim() });
      startCountdown();
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === "KOL_EMAIL_EXISTS") {
        emailExists.value = true;
        return;
      }
      throw error;
    }
  });
}

function register() {
  return submit(async () => {
    emailExists.value = false;
    const address = email.value.trim();
    const url = profileUrl.value.trim();
    const region = country.value.trim();
    if (!validEmail(address)) {
      validation.value = "auth.emailInvalid";
      return;
    }
    if (!/^\d{6}$/.test(emailCode.value.trim())) {
      validation.value = "register.emailCodeInvalid";
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
    if (!/^\+[0-9]{8,15}$/.test(whatsapp.value.trim())) {
      validation.value = "register.whatsappInvalid";
      return;
    }
    if (!platform.value) {
      validation.value = "register.platformRequired";
      return;
    }
    if (!handle.value.trim() || handle.value.trim().length > 64) {
      validation.value = "register.handleRequired";
      return;
    }
    if (url && !/^https:\/\/[^\s/?#]+\S*$/.test(url)) {
      validation.value = "register.urlInvalid";
      return;
    }
    if (region && !/^[A-Za-z]{2}$/.test(region)) {
      validation.value = "register.countryInvalid";
      return;
    }
    try {
      const result = await registerKol({
        code: code.value,
        email: address,
        emailCode: emailCode.value.trim(),
        password: password.value,
        whatsapp: whatsapp.value.trim(),
        primaryPlatform: platform.value,
        primaryHandle: handle.value.trim(),
        primaryUrl: blank(url),
        name: blank(name.value),
        country: blank(region)
      });
      session.acceptSession(result);
      await router.replace("/");
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === "KOL_EMAIL_EXISTS") {
        emailExists.value = true;
        return;
      }
      throw error;
    }
  });
}

watch(code, () => void loadPreview(), { immediate: true });
onUnmounted(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <AuthShell :title="t('register.title')">
    <p v-if="linkState === 'invalid'" class="register-invalid">{{ t("register.invalidLink") }}</p>
    <template v-else-if="linkState === 'ready'">
      <p class="register-join">{{ t("register.join", { company: tenantName }) }}</p>
      <el-alert v-if="errorText" role="alert" type="error" :closable="false" :title="errorText" />
      <el-alert v-if="emailExists" type="warning" :closable="false">
        {{ t("register.emailExists") }}
        <RouterLink class="register-login" :to="{ path: '/login', query: { email: email.trim() } }">
          {{ t("register.goToLogin") }}
        </RouterLink>
      </el-alert>
      <el-form label-position="top" novalidate @submit.prevent="register">
        <el-form-item :label="t('auth.email')" for="register-email">
          <div class="register-email">
            <el-input
              id="register-email"
              v-model="email"
              type="email"
              autocomplete="email"
              :disabled="busy"
            />
            <el-button
              id="register-send"
              native-type="button"
              :loading="busy"
              :disabled="busy"
              @click="sendCode"
              >{{
                seconds > 0 ? t("register.resendIn", { seconds }) : t("register.sendCode")
              }}</el-button
            >
          </div>
        </el-form-item>
        <el-form-item :label="t('register.emailCode')" for="register-email-code">
          <el-input
            id="register-email-code"
            v-model="emailCode"
            inputmode="numeric"
            autocomplete="one-time-code"
            maxlength="6"
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
        <el-form-item :label="t('register.profileUrl')" for="register-url">
          <el-input
            id="register-url"
            v-model="profileUrl"
            type="url"
            placeholder="https://"
            :disabled="busy"
          />
        </el-form-item>
        <el-collapse>
          <el-collapse-item :title="t('register.more')" name="more">
            <el-form-item :label="t('register.name')" for="register-name">
              <el-input id="register-name" v-model="name" maxlength="64" :disabled="busy" />
            </el-form-item>
            <el-form-item :label="t('register.country')" for="register-country">
              <el-input id="register-country" v-model="country" maxlength="2" :disabled="busy" />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
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
.register-join,
.register-invalid {
  margin: -8px 0 20px;
  text-align: center;
}
.register-email {
  display: flex;
  gap: 8px;
  width: 100%;
}
.register-email .el-input {
  flex: 1;
}
.register-platform {
  width: 100%;
}
</style>
