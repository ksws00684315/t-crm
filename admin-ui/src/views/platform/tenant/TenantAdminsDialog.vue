<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { reactive, ref, watch } from "vue";
import {
  createTenantAdmin,
  listTenantAdmins,
  resetTenantAdminPassword,
  type TenantAdminVO,
  type TenantVO
} from "@/api/platform/tenant";
import { formatInstant } from "@/utils/format";
import { notifyError } from "@/utils/notify";
import {
  rule,
  serverFieldErrors,
  validateEmail,
  validateNewPassword,
  validateNickname,
  validateUsername
} from "@/utils/validation";

defineOptions({ name: "TenantAdminsDialog" });

const props = defineProps<{ modelValue: boolean; tenant: TenantVO | null }>();
const emit = defineEmits<{ (event: "update:modelValue", value: boolean): void }>();

const loading = ref(false);
const rows = ref<TenantAdminVO[]>([]);
const createVisible = ref(false);
const resetVisible = ref(false);
const resettingAdmin = ref<TenantAdminVO | null>(null);
const createFormRef = ref<FormInstance>();
const resetFormRef = ref<FormInstance>();
const creating = ref(false);
const resetting = ref(false);
const createErrors = ref<Record<string, string>>({});
const resetErrors = ref<Record<string, string>>({});
const createForm = reactive({ username: "", nickname: "", email: "", password: "" });
const resetForm = reactive({ password: "" });
const createRules: FormRules = {
  username: [rule(validateUsername)],
  nickname: [rule(validateNickname)],
  email: [rule(validateEmail)],
  password: [rule(validateNewPassword)]
};
const resetRules: FormRules = { password: [rule(validateNewPassword)] };

async function load() {
  if (!props.tenant) return;
  loading.value = true;
  try {
    rows.value = await listTenantAdmins(props.tenant.id);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.modelValue, props.tenant?.id] as const,
  ([open]) => {
    if (open) void load();
  },
  { immediate: true }
);

function close() {
  emit("update:modelValue", false);
}

function openCreate() {
  createErrors.value = {};
  createForm.username = "";
  createForm.nickname = "";
  createForm.email = "";
  createForm.password = "";
  createVisible.value = true;
}

async function submitCreate() {
  createErrors.value = {};
  if (
    !props.tenant ||
    !createFormRef.value ||
    !(await createFormRef.value.validate().catch(() => false))
  ) {
    return;
  }
  creating.value = true;
  try {
    await createTenantAdmin(props.tenant.id, {
      username: createForm.username,
      nickname: createForm.nickname.trim(),
      email: createForm.email.trim() || null,
      password: createForm.password
    });
    ElMessage.success("超级管理员已创建");
    createVisible.value = false;
    await load();
  } catch (error) {
    createErrors.value = serverFieldErrors(error, ["username", "nickname", "email", "password"]);
    notifyError(error);
  } finally {
    creating.value = false;
  }
}

function openReset(row: TenantAdminVO) {
  resettingAdmin.value = row;
  resetForm.password = "";
  resetErrors.value = {};
  resetVisible.value = true;
}

async function submitReset() {
  if (
    !props.tenant ||
    !resettingAdmin.value ||
    !resetFormRef.value ||
    !(await resetFormRef.value.validate().catch(() => false))
  ) {
    return;
  }
  resetting.value = true;
  resetErrors.value = {};
  try {
    await resetTenantAdminPassword(props.tenant.id, resettingAdmin.value.id, resetForm.password);
    ElMessage.success("该管理员已在所有设备上退出");
    resetVisible.value = false;
  } catch (error) {
    resetErrors.value = serverFieldErrors(error, ["password"]);
    notifyError(error);
  } finally {
    resetting.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="`超级管理员：${tenant?.name ?? ''}`"
    width="900px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="actions">
      <el-button type="primary" @click="openCreate">新建超级管理员</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" row-key="id" border>
      <el-table-column prop="username" label="用户名" min-width="130" />
      <el-table-column prop="nickname" label="昵称" min-width="120" />
      <el-table-column prop="email" label="邮箱" min-width="180" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="(row as TenantAdminVO).status === 1 ? 'success' : 'info'">
            {{ (row as TenantAdminVO).status === 1 ? "启用" : "停用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" width="180">
        <template #default="{ row }">
          {{ formatInstant((row as TenantAdminVO).lastLoginTime) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="warning" @click="openReset(row as TenantAdminVO)">
            重置密码
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #footer><el-button @click="close">关闭</el-button></template>
  </el-dialog>

  <el-dialog v-model="createVisible" title="新建超级管理员" width="500px">
    <el-form
      ref="createFormRef"
      :model="createForm"
      :rules="createRules"
      label-width="100px"
      @submit.prevent
    >
      <el-form-item label="用户名" prop="username" :error="createErrors.username">
        <el-input v-model="createForm.username" maxlength="64" />
      </el-form-item>
      <el-form-item label="昵称" prop="nickname" :error="createErrors.nickname">
        <el-input v-model="createForm.nickname" maxlength="64" />
      </el-form-item>
      <el-form-item label="邮箱" prop="email" :error="createErrors.email">
        <el-input v-model="createForm.email" maxlength="254" />
      </el-form-item>
      <el-form-item label="初始密码" prop="password" :error="createErrors.password">
        <el-input
          v-model="createForm.password"
          type="password"
          show-password
          autocomplete="new-password"
          maxlength="72"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="resetVisible"
    :title="`重置密码：${resettingAdmin?.username ?? ''}`"
    width="460px"
  >
    <el-form
      ref="resetFormRef"
      :model="resetForm"
      :rules="resetRules"
      label-width="90px"
      @submit.prevent
    >
      <el-form-item label="新密码" prop="password" :error="resetErrors.password">
        <el-input
          v-model="resetForm.password"
          type="password"
          show-password
          autocomplete="new-password"
          maxlength="72"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="resetVisible = false">取消</el-button>
      <el-button type="primary" :loading="resetting" @click="submitReset">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.actions {
  margin-bottom: 12px;
}
</style>
