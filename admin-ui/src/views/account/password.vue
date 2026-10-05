<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
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
      if (!value) return "请再次输入新密码";
      return value === form.newPassword ? null : "两次输入的新密码不一致";
    })
  ]
};

async function submit() {
  serverErrors.value = {};
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  saving.value = true;
  try {
    await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword });
    ElMessage.success("密码已修改，请使用新密码重新登录");
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
      <el-form-item label="原密码" prop="oldPassword" :error="serverErrors.oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password maxlength="72" />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword" :error="serverErrors.newPassword">
        <el-input v-model="form.newPassword" type="password" show-password maxlength="72" />
      </el-form-item>
      <el-form-item label="确认新密码" prop="confirm">
        <el-input v-model="form.confirm" type="password" show-password maxlength="72" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="submit">修改密码</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<style scoped>
.password {
  max-width: 520px;
}
</style>
