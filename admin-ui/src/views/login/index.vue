<script setup lang="ts">
import type { FormInstance, FormRules } from "element-plus";
import { reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import CaptchaInput from "@/components/CaptchaInput.vue";
import LocaleSwitch from "@/components/LocaleSwitch.vue";
import { useUserStore } from "@/stores/user";
import { siteName } from "@/stores/site-config";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";
import {
  rule,
  validateCaptcha,
  validateExistingPassword,
  validateUsername
} from "@/utils/validation";

defineOptions({ name: "LoginPage" });

const { t } = useI18n();
const router = useRouter();
const route = useRoute();
const user = useUserStore();

const formRef = ref<FormInstance>();
const loading = ref(false);
const captchaRef = ref<InstanceType<typeof CaptchaInput>>();
const form = reactive({ username: "", password: "", captchaCode: "" });
const rules: FormRules = {
  username: [rule(validateUsername)],
  password: [rule(validateExistingPassword)],
  // Skipped only when the backend reports captchas disabled (dev/test property).
  captchaCode: [
    rule(value => (captchaRef.value?.enabled === false ? null : validateCaptcha(value)))
  ]
};

/** Only same-site paths are honoured, never an absolute URL. */
function safeRedirect(value: unknown): string {
  return typeof value === "string" && value.startsWith("/") && !value.startsWith("//")
    ? value
    : "/";
}

async function submit() {
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  loading.value = true;
  try {
    await user.login(form.username, form.password, captchaRef.value?.answer());
    await router.replace(safeRedirect(route.query.redirect));
  } catch (error) {
    // The backend consumed the captcha whatever the outcome, so a new one is always needed.
    void captchaRef.value?.refresh();
    // A wrong or expired captcha keeps the password; any other failure clears it.
    if (!(error instanceof ApiError && error.errorCode === "AUTH_CAPTCHA_INVALID")) {
      form.password = "";
    }
    notifyError(error);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login">
    <el-card class="login__card" shadow="always">
      <h1 class="login__title">{{ siteName }} {{ t("login.titleSuffix") }}</h1>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item :label="t('login.username')" prop="username">
          <el-input v-model="form.username" autocomplete="username" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('login.password')" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            maxlength="72"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item
          v-show="captchaRef?.enabled !== false"
          :label="t('captcha.label')"
          prop="captchaCode"
        >
          <CaptchaInput ref="captchaRef" v-model="form.captchaCode" @enter="submit" />
        </el-form-item>
        <el-button type="primary" class="login__submit" :loading="loading" @click="submit">
          {{ t("login.submit") }}
        </el-button>
      </el-form>
    </el-card>
    <!-- After the form in the DOM so existing tests still address the first two inputs. -->
    <div class="login__bar">
      <LocaleSwitch />
    </div>
  </div>
</template>

<style scoped>
.login {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 16px;
}
.login__bar {
  position: absolute;
  top: 16px;
  right: 16px;
}
.login__card {
  width: 100%;
  max-width: 380px;
}
.login__title {
  margin: 0 0 24px;
  font-size: 22px;
  text-align: center;
}
.login__submit {
  width: 100%;
}
</style>
