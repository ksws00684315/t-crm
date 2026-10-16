<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { activate, resetPassword } from "@/api/auth";
import AuthShell from "@/components/AuthShell.vue";
import { useSessionStore } from "@/stores/session";
import { validPassword } from "@/utils/auth";
import { useSubmission } from "@/utils/useSubmission";

defineOptions({ name: "PasswordLinkPage" });
const props = defineProps<{ mode: "activate" | "reset" }>();
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const password = ref("");
const confirm = ref("");
const token = computed(() => (typeof route.query.token === "string" ? route.query.token : ""));
const { busy, validation, failure, errorText, submit } = useSubmission();
const invalid = computed(
  () => !token.value || failure.value?.errorCode === "KOL_AUTH_TOKEN_INVALID"
);
watch(
  () => [route.fullPath, props.mode],
  () => {
    password.value = "";
    confirm.value = "";
    failure.value = null;
    validation.value = "";
  }
);
function save() {
  return submit(async () => {
    if (invalid.value) return;
    if (!validPassword(password.value)) {
      validation.value = "auth.passwordRule";
      return;
    }
    if (password.value !== confirm.value) {
      validation.value = "auth.passwordMismatch";
      return;
    }
    if (props.mode === "activate") {
      session.acceptSession(await activate({ token: token.value, password: password.value }));
      await router.replace("/");
    } else {
      await resetPassword({ token: token.value, password: password.value });
      session.clearSession();
      await router.replace({ path: "/login", query: { reset: "success" } });
    }
    password.value = "";
    confirm.value = "";
  });
}
</script>
<template>
  <AuthShell :title="t(mode === 'activate' ? 'auth.activateTitle' : 'auth.resetTitle')">
    <el-alert
      v-if="invalid"
      role="alert"
      type="error"
      :closable="false"
      :title="t('errors.KOL_AUTH_TOKEN_INVALID')"
    />
    <el-form v-else label-position="top" novalidate @submit.prevent="save">
      <el-alert v-if="errorText" role="alert" type="error" :closable="false" :title="errorText" />
      <p>{{ t("auth.passwordRule") }}</p>
      <el-form-item :label="t('auth.newPassword')" for="link-password"
        ><el-input
          id="link-password"
          v-model="password"
          type="password"
          autocomplete="new-password"
          show-password
          :disabled="busy"
      /></el-form-item>
      <el-form-item :label="t('auth.confirmPassword')" for="link-confirm"
        ><el-input
          id="link-confirm"
          v-model="confirm"
          type="password"
          autocomplete="new-password"
          show-password
          :disabled="busy"
      /></el-form-item>
      <el-button
        native-type="submit"
        type="primary"
        class="auth-submit"
        :loading="busy"
        :disabled="busy"
        >{{ t(mode === "activate" ? "auth.activateSubmit" : "auth.resetSubmit") }}</el-button
      >
    </el-form>
    <div class="auth-links">
      <RouterLink to="/login">{{ t("auth.backToLogin") }}</RouterLink>
    </div>
  </AuthShell>
</template>
