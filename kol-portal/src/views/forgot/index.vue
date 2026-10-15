<script setup lang="ts">
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { forgotPassword } from "@/api/auth";
import AuthShell from "@/components/AuthShell.vue";
import { validEmail } from "@/utils/auth";
import { useSubmission } from "@/utils/useSubmission";

defineOptions({ name: "ForgotPasswordPage" });
const { t } = useI18n();
const email = ref("");
const sent = ref(false);
const { busy, validation, errorText, submit } = useSubmission();
function send() {
  return submit(async () => {
    if (!validEmail(email.value.trim())) {
      validation.value = "auth.emailInvalid";
      return;
    }
    await forgotPassword({ email: email.value.trim() });
    sent.value = true;
    email.value = "";
  });
}
</script>
<template>
  <AuthShell :title="t('auth.forgotTitle')">
    <el-alert v-if="sent" type="success" :closable="false" :title="t('auth.forgotSent')" />
    <el-form v-else label-position="top" novalidate @submit.prevent="send">
      <el-alert v-if="errorText" role="alert" type="error" :closable="false" :title="errorText" />
      <el-form-item :label="t('auth.email')" for="forgot-email"
        ><el-input
          id="forgot-email"
          v-model="email"
          type="email"
          autocomplete="email"
          :disabled="busy"
      /></el-form-item>
      <el-button
        native-type="submit"
        type="primary"
        class="auth-submit"
        :loading="busy"
        :disabled="busy"
        >{{ t("auth.sendReset") }}</el-button
      >
    </el-form>
    <div class="auth-links">
      <RouterLink to="/login">{{ t("auth.backToLogin") }}</RouterLink>
    </div>
  </AuthShell>
</template>
