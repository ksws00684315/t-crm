<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { changePassword, getMe } from "@/api/auth";
import { useSessionStore } from "@/stores/session";
import { safeSheetUrl, validPassword } from "@/utils/auth";
import { useSubmission } from "@/utils/useSubmission";

defineOptions({ name: "AccountPage" });
const { t } = useI18n();
const session = useSessionStore();
const oldPassword = ref("");
const newPassword = ref("");
const confirm = ref("");
const changed = ref(false);
const { busy, validation, errorText, submit } = useSubmission();
const profileRequest = useSubmission();
const profileError = profileRequest.errorText;
const profileLoading = profileRequest.busy;
const sheetUrl = computed(() => safeSheetUrl(session.kol?.sheetUrl));
function loadProfile() {
  return profileRequest.submit(async () => {
    session.updateProfile(await getMe());
  });
}
onMounted(loadProfile);
function save() {
  return submit(async () => {
    changed.value = false;
    if (!validPassword(oldPassword.value) || !validPassword(newPassword.value)) {
      validation.value = "auth.passwordRule";
      return;
    }
    if (newPassword.value !== confirm.value) {
      validation.value = "auth.passwordMismatch";
      return;
    }
    session.acceptSession(
      await changePassword({ oldPassword: oldPassword.value, newPassword: newPassword.value })
    );
    oldPassword.value = "";
    newPassword.value = "";
    confirm.value = "";
    changed.value = true;
  });
}
</script>
<template>
  <section class="account">
    <RouterLink to="/">{{ t("common.backToHome") }}</RouterLink>
    <h1>{{ t("account.title") }}</h1>
    <el-alert
      v-if="profileError"
      role="alert"
      type="error"
      :closable="false"
      :title="profileError"
    />
    <el-button v-if="profileError" :loading="profileLoading" @click="loadProfile">{{
      t("account.retry")
    }}</el-button>
    <dl v-if="session.kol" class="profile">
      <template
        v-for="field in ['kolCode', 'name', 'email', 'country', 'tenantName'] as const"
        :key="field"
        ><dt>{{ t(`account.${field}`) }}</dt>
        <dd>{{ session.kol[field] || t("account.unavailable") }}</dd></template
      >
      <template v-if="sheetUrl"
        ><dt>{{ t("account.sheet") }}</dt>
        <dd>
          <a :href="sheetUrl" target="_blank" rel="noopener noreferrer">{{
            t("account.openSheet")
          }}</a>
        </dd></template
      >
    </dl>
    <el-card class="password-card" shadow="never">
      <h2>{{ t("account.changePassword") }}</h2>
      <el-alert
        v-if="changed"
        type="success"
        :closable="false"
        :title="t('account.passwordChanged')"
      />
      <el-alert v-if="errorText" role="alert" type="error" :closable="false" :title="errorText" />
      <p>{{ t("auth.passwordRule") }}</p>
      <el-form label-position="top" novalidate @submit.prevent="save">
        <el-form-item :label="t('account.oldPassword')" for="old-password"
          ><el-input
            id="old-password"
            v-model="oldPassword"
            type="password"
            autocomplete="current-password"
            show-password
            :disabled="busy"
        /></el-form-item>
        <el-form-item :label="t('auth.newPassword')" for="new-password"
          ><el-input
            id="new-password"
            v-model="newPassword"
            type="password"
            autocomplete="new-password"
            show-password
            :disabled="busy"
        /></el-form-item>
        <el-form-item :label="t('auth.confirmPassword')" for="confirm-password"
          ><el-input
            id="confirm-password"
            v-model="confirm"
            type="password"
            autocomplete="new-password"
            show-password
            :disabled="busy"
        /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="busy">{{
          t("account.changePassword")
        }}</el-button>
      </el-form>
    </el-card>
  </section>
</template>
<style scoped>
.profile {
  display: grid;
  grid-template-columns: minmax(90px, auto) minmax(0, 1fr);
  gap: 12px 24px;
}
dt {
  color: var(--el-text-color-secondary);
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.password-card {
  max-width: 480px;
  margin-top: 24px;
}
h2 {
  margin-top: 0;
  font-size: 18px;
}
.el-alert {
  margin-bottom: 16px;
}
:deep(.el-button),
:deep(.el-input__wrapper) {
  min-height: 44px;
}
@media (max-width: 480px) {
  .password-card,
  .el-button {
    width: 100%;
  }
}
</style>
