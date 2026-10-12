<script setup lang="ts">
import { Lock } from "@element-plus/icons-vue";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import { messageForErrorCode } from "@/api/error-codes";
import CaptchaInput from "@/components/CaptchaInput.vue";
import { useLockStore } from "@/stores/lock";
import { useReauthStore } from "@/stores/reauth";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import { ApiError } from "@/utils/http/error";
import { rule, validateCaptcha, validateExistingPassword } from "@/utils/validation";

/**
 * Full-screen lock (research report P0-3). Unlocking is a login for the same account
 * (password + captcha per ADR-017), so failures count toward the account lockout exactly as on the
 * login page. Tabs and page state stay mounted underneath.
 */
defineOptions({ name: "LockScreen" });

const { t } = useI18n();
const router = useRouter();
const user = useUserStore();
const lock = useLockStore();
const tabs = useTabsStore();
const reauth = useReauthStore();

const formRef = ref<FormInstance>();
const captchaRef = ref<InstanceType<typeof CaptchaInput>>();
const loading = ref(false);
const errorText = ref("");
const captchaError = ref("");
const form = reactive({ password: "", captchaCode: "" });
const rules: FormRules = {
  password: [rule(validateExistingPassword)],
  captchaCode: [
    rule(value => (captchaRef.value?.enabled === false ? null : validateCaptcha(value)))
  ]
};

watch(
  () => lock.locked,
  locked => {
    if (!locked) return;
    form.password = "";
    errorText.value = "";
    captchaError.value = "";
  }
);

async function signOut(): Promise<void> {
  reauth.close();
  tabs.reset();
  await user.logout();
  await router.replace("/login");
}

async function submit(): Promise<void> {
  if (!user.user || !formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  const previousId = user.user.id;
  loading.value = true;
  errorText.value = "";
  captchaError.value = "";
  try {
    await user.login(user.user.username, form.password, captchaRef.value?.answer());
  } catch (error) {
    void captchaRef.value?.refresh();
    if (error instanceof ApiError && error.errorCode === "AUTH_ACCOUNT_LOCKED") {
      ElMessage.warning(messageForErrorCode("AUTH_ACCOUNT_LOCKED"));
      await signOut();
      return;
    }
    if (error instanceof ApiError && error.errorCode === "AUTH_CAPTCHA_INVALID") {
      captchaError.value = error.displayMessage;
      return;
    }
    form.password = "";
    errorText.value = error instanceof ApiError ? error.displayMessage : t("lock.failed");
    return;
  } finally {
    loading.value = false;
  }
  form.password = "";
  lock.unlock();
  // The login also renewed an expired session, so a pending reauth prompt is no longer needed.
  reauth.close();
  if (user.user?.id !== previousId) {
    tabs.reset();
    await router.replace("/");
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="lock.locked"
      class="lock-screen"
      role="dialog"
      aria-modal="true"
      :aria-label="t('lock.title')"
      data-test="lock-screen"
    >
      <div class="lock-screen__card">
        <div class="lock-screen__icon">
          <el-icon :size="28"><Lock /></el-icon>
        </div>
        <h2 class="lock-screen__title">{{ t("lock.title") }}</h2>
        <p class="lock-screen__hint">
          {{ t("lock.hint", { username: user.user?.username ?? "" }) }}
        </p>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
          <el-form-item :label="t('common.password')" prop="password" :error="errorText">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              maxlength="72"
              autocomplete="current-password"
              data-test="lock-password"
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-form-item
            v-show="captchaRef?.enabled !== false"
            :label="t('captcha.label')"
            prop="captchaCode"
            :error="captchaError"
          >
            <CaptchaInput ref="captchaRef" v-model="form.captchaCode" @enter="submit" />
          </el-form-item>
          <div class="lock-screen__actions">
            <el-button data-test="lock-signout" @click="signOut">{{
              t("common.signOut")
            }}</el-button>
            <el-button type="primary" :loading="loading" data-test="lock-submit" @click="submit">
              {{ t("lock.unlock") }}
            </el-button>
          </div>
        </el-form>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.lock-screen {
  position: fixed;
  inset: 0;
  /* Above Element dialogs (2000+) so the reauth prompt and page content stay hidden. */
  z-index: 4000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--tcrm-color-page, #0b1220) 72%, transparent);
  backdrop-filter: blur(14px);
}
.lock-screen__card {
  width: min(380px, calc(100vw - 32px));
  padding: var(--tcrm-space-5, 24px);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  box-shadow: 0 20px 48px rgb(0 0 0 / 18%);
}
.lock-screen__icon {
  display: flex;
  justify-content: center;
  color: var(--tcrm-color-primary-text, var(--el-color-primary));
}
.lock-screen__title {
  margin: var(--tcrm-space-2) 0 var(--tcrm-space-1, 4px);
  text-align: center;
  font-size: 18px;
}
.lock-screen__hint {
  margin: 0 0 var(--tcrm-space-3);
  text-align: center;
  color: var(--tcrm-color-text-secondary);
}
.lock-screen__actions {
  display: flex;
  justify-content: space-between;
  gap: var(--tcrm-space-2);
}
</style>
