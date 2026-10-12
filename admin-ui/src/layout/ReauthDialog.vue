<script setup lang="ts">
import type { FormInstance, FormRules } from "element-plus";
import { onBeforeUnmount, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import { useReauthStore } from "@/stores/reauth";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import { rule, validateExistingPassword } from "@/utils/validation";

defineOptions({ name: "ReauthDialog" });

const { t } = useI18n();
const router = useRouter();
const user = useUserStore();
const tabs = useTabsStore();
const reauth = useReauthStore();

const formRef = ref<FormInstance>();
const loading = ref(false);
const errorText = ref("");
const form = reactive({ password: "" });
const rules: FormRules = { password: [rule(validateExistingPassword)] };

watch(
  () => reauth.visible,
  open => {
    if (!open) return;
    form.password = "";
    errorText.value = "";
  }
);

/** Layout unmount must not leave visible=true for the next login. */
onBeforeUnmount(() => {
  reauth.close();
});

async function leaveForLogin(): Promise<void> {
  tabs.reset();
  reauth.close();
  user.clearSession();
  await router.replace("/login");
}

async function submit(): Promise<void> {
  if (!user.user || !formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  const previousId = user.user.id;
  loading.value = true;
  errorText.value = "";
  try {
    await user.login(user.user.username, form.password);
  } catch (error) {
    form.password = "";
    // AUTH_ACCOUNT_LOCKED is HTTP 429, so the 401 handler never sees it.
    if (error instanceof ApiError && error.errorCode === "AUTH_ACCOUNT_LOCKED") {
      await leaveForLogin();
      return;
    }
    errorText.value = error instanceof ApiError ? error.displayMessage : t("reauth.failed");
    return;
  } finally {
    loading.value = false;
  }
  reauth.close();
  if (user.user?.id !== previousId) {
    // The login response replaced the session. Clearing it again would bounce to /login,
    // so the new session stays, the previous user's tabs are dropped, and the guard picks the home.
    tabs.reset();
    await router.replace("/");
  }
}

async function switchAccount(): Promise<void> {
  // The server session is already gone. Calling logout would 401 and open this dialog again.
  await leaveForLogin();
}
</script>

<template>
  <el-dialog
    :model-value="reauth.visible"
    :title="t('reauth.title')"
    width="420px"
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    data-test="reauth-dialog"
  >
    <p class="reauth__hint">{{ t("reauth.hint") }}</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
      <el-form-item :label="t('common.username')">
        <el-input :model-value="user.user?.username ?? ''" readonly data-test="reauth-username" />
      </el-form-item>
      <el-form-item :label="t('common.password')" prop="password" :error="errorText">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          maxlength="72"
          data-test="reauth-password"
          @keyup.enter="submit"
        />
      </el-form-item>
      <div class="reauth__actions">
        <el-button data-test="reauth-switch" @click="switchAccount">{{
          t("reauth.switch")
        }}</el-button>
        <el-button type="primary" data-test="reauth-submit" :loading="loading" @click="submit">
          {{ t("reauth.submit") }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.reauth__hint {
  margin: 0 0 var(--tcrm-space-3);
  color: var(--tcrm-color-text-secondary);
}
.reauth__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--tcrm-space-2);
}
</style>
