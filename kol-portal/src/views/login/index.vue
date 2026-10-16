<script setup lang="ts">
import { ref, shallowRef } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { login, selectCompany, type CompanySelection, type AuthSession } from "@/api/auth";
import AuthShell from "@/components/AuthShell.vue";
import { useSessionStore } from "@/stores/session";
import { safeRedirect, validEmail, validPassword } from "@/utils/auth";
import { ApiError } from "@/utils/http/error";
import { useSubmission } from "@/utils/useSubmission";

defineOptions({ name: "LoginPage" });
const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const email = ref(typeof route.query.email === "string" ? route.query.email : "");
const password = ref("");
const selection = shallowRef<CompanySelection | null>(null);
const tenantId = ref("");
const { busy, validation, errorText, submit } = useSubmission();

async function finish(result: AuthSession) {
  session.acceptSession(result);
  password.value = "";
  selection.value = null;
  await router.replace(safeRedirect(route.query.redirect));
}
function signIn() {
  return submit(async () => {
    if (!validEmail(email.value.trim())) {
      validation.value = "auth.emailInvalid";
      return;
    }
    if (!validPassword(password.value)) {
      validation.value = "auth.passwordRule";
      return;
    }
    const result = await login({ email: email.value.trim(), password: password.value });
    if ("selectionToken" in result) {
      selection.value = result;
      tenantId.value = "";
      password.value = "";
    } else await finish(result);
  });
}
function chooseCompany() {
  return submit(async () => {
    if (!selection.value || !tenantId.value) {
      validation.value = "login.chooseCompany";
      return;
    }
    try {
      await finish(
        await selectCompany({
          selectionToken: selection.value.selectionToken,
          tenantId: tenantId.value
        })
      );
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === "KOL_LOGIN_SELECTION_INVALID")
        selection.value = null;
      throw error;
    }
  });
}
</script>

<template>
  <AuthShell :title="t('login.title')">
    <el-alert
      v-if="route.query.reset === 'success'"
      type="success"
      :closable="false"
      :title="t('auth.resetSuccess')"
    />
    <el-alert v-if="errorText" role="alert" type="error" :closable="false" :title="errorText" />
    <el-form label-position="top" novalidate @submit.prevent="signIn">
      <el-form-item :label="t('auth.email')" for="login-email">
        <el-input
          id="login-email"
          v-model="email"
          type="email"
          autocomplete="username"
          :disabled="busy"
        />
      </el-form-item>
      <el-form-item :label="t('login.password')" for="login-password">
        <el-input
          id="login-password"
          v-model="password"
          type="password"
          autocomplete="current-password"
          show-password
          :disabled="busy"
        />
      </el-form-item>
      <el-button
        native-type="submit"
        type="primary"
        class="auth-submit"
        :loading="busy"
        :disabled="busy || !!selection"
        >{{ t("login.submit") }}</el-button
      >
    </el-form>
    <div class="auth-links">
      <RouterLink to="/forgot">{{ t("login.forgot") }}</RouterLink>
    </div>
    <el-dialog
      :model-value="!!selection"
      :title="t('login.selectCompany')"
      width="min(420px, calc(100% - 32px))"
      :close-on-click-modal="!busy"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      @close="selection = null"
    >
      <el-alert v-if="errorText" role="alert" type="error" :closable="false" :title="errorText" />
      <el-radio-group
        v-model="tenantId"
        class="companies"
        :aria-label="t('login.selectCompany')"
        :disabled="busy"
      >
        <el-radio
          v-for="company in selection?.companies"
          :key="company.tenantId"
          :value="company.tenantId"
          >{{ company.tenantName }}</el-radio
        >
      </el-radio-group>
      <template #footer
        ><el-button
          type="primary"
          :loading="busy"
          :disabled="busy || !tenantId"
          @click="chooseCompany"
          >{{ t("login.continue") }}</el-button
        ></template
      >
    </el-dialog>
  </AuthShell>
</template>

<style scoped>
.companies {
  display: flex;
  flex-direction: column;
  align-items: stretch;
}
.companies :deep(.el-radio) {
  min-height: 44px;
  height: auto;
  margin: 0;
  white-space: normal;
}
.companies :deep(.el-radio__label) {
  white-space: normal;
  overflow-wrap: anywhere;
}
</style>
