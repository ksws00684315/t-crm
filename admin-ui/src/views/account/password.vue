<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import { translate } from "@/locales";
import { changePassword } from "@/api/auth";
import { useUserStore } from "@/stores/user";
import { notifyError } from "@/utils/notify";
import {
  rule,
  serverFieldErrors,
  validateExistingPassword,
  validateNewPassword
} from "@/utils/validation";

defineOptions({ name: "AccountPasswordPage" });

const { t } = useI18n();
const router = useRouter();
const user = useUserStore();
const formRef = ref<FormInstance>();
const saving = ref(false);
const form = reactive({ oldPassword: "", newPassword: "", confirm: "" });
const serverErrors = ref<Record<string, string>>({});

const rules: FormRules = {
  oldPassword: [rule(validateExistingPassword)],
  newPassword: [rule(validateNewPassword)],
  confirm: [
    rule(value => {
      if (!value) return translate("validation.passwordAgain");
      return value === form.newPassword ? null : translate("validation.passwordMismatch");
    })
  ]
};

async function submit() {
  serverErrors.value = {};
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  saving.value = true;
  try {
    await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword });
    ElMessage.success(t("password.success"));
    // The backend ends every session of this account after a password change.
    user.clearSession();
    await router.replace("/login");
  } catch (error) {
    serverErrors.value = serverFieldErrors(error, ["oldPassword", "newPassword"]);
    notifyError(error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-card shadow="never" class="password">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" @submit.prevent>
      <el-form-item :label="t('password.old')" prop="oldPassword" :error="serverErrors.oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password maxlength="72" />
      </el-form-item>
      <el-form-item
        :label="t('password.next')"
        prop="newPassword"
        :error="serverErrors.newPassword"
      >
        <el-input v-model="form.newPassword" type="password" show-password maxlength="72" />
      </el-form-item>
      <el-form-item :label="t('password.confirm')" prop="confirm">
        <el-input v-model="form.confirm" type="password" show-password maxlength="72" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="submit">{{
          t("password.submit")
        }}</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<style scoped>
.password {
  max-width: 520px;
}
</style>
