<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { reactive, ref, watch } from "vue";
import { resetUserPassword } from "@/api/system/user";
import type { UserVO } from "@/api/types";
import { notifyError } from "@/utils/notify";
import { rule, serverFieldErrors, validateNewPassword } from "@/utils/validation";

defineOptions({ name: "ResetPasswordDialog" });

const props = defineProps<{ modelValue: boolean; user: UserVO | null }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const formRef = ref<FormInstance>();
const saving = ref(false);
const serverErrors = ref<Record<string, string>>({});
const form = reactive({ password: "" });
const rules: FormRules = { password: [rule(validateNewPassword)] };

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    form.password = "";
    serverErrors.value = {};
    formRef.value?.clearValidate();
  }
);

function close() {
  emit("update:modelValue", false);
}

async function submit() {
  serverErrors.value = {};
  if (!props.user || !formRef.value || !(await formRef.value.validate().catch(() => false))) return;
  saving.value = true;
  try {
    await resetUserPassword(props.user.id, form.password);
    ElMessage.success("密码已重置，该用户需使用新密码重新登录");
    close();
  } catch (error) {
    serverErrors.value = serverFieldErrors(error, ["password"]);
    notifyError(error);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="`重置密码：${user?.username ?? ''}`"
    width="440px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" @submit.prevent>
      <el-form-item label="新密码" prop="password" :error="serverErrors.password">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          autocomplete="new-password"
          maxlength="72"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
    </template>
  </el-dialog>
</template>
