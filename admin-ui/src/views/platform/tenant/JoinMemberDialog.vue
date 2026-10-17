<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import type { TenantVO } from "@/api/platform/tenant";
import {
  addTenantMember,
  findPlatformUser,
  resetPlatformUserPassword,
  updatePlatformUser,
  updatePlatformUserStatus,
  type PlatformUserVO
} from "@/api/platform/user";
import type { CompanyRole, DataScope, PostTemplate } from "@/api/types";
import { notifyError } from "@/utils/notify";
import { POST_TEMPLATES, postLabel, roleLabel, SELECTABLE_ROLES } from "@/utils/permission";
import {
  rule,
  serverFieldErrors,
  validateEmail,
  validateNewPassword,
  validateNickname,
  validateUsername
} from "@/utils/validation";

/**
 * Platform administrator: add an existing account to a company and manage the account itself
 * (task 21b §4.5). Account actions apply to every company of that account.
 */
defineOptions({ name: "JoinMemberDialog" });

const { t } = useI18n();

const props = defineProps<{ modelValue: boolean; tenant: TenantVO | null }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "joined"): void;
}>();

const title = computed(() => t("member.joinTitle", { name: props.tenant?.name ?? "" }));
const lookupFormRef = ref<FormInstance>();
const lookupForm = reactive({ username: "" });
const lookupRules: FormRules = { username: [rule(validateUsername)] };
const looking = ref(false);
/** Username of the last finished lookup; null before the first one. */
const lookedUp = ref<string | null>(null);
const account = ref<PlatformUserVO | null>(null);

const joinFormRef = ref<FormInstance>();
const joining = ref(false);
const joinForm = reactive({
  role: "STAFF" as CompanyRole,
  postTemplate: "" as PostTemplate | "",
  dataScope: "" as DataScope | ""
});
const joinRules = computed<FormRules>(() => ({
  postTemplate: [
    {
      validator: (_rule, value: string, callback: (error?: Error) => void) => {
        if (joinForm.role === "STAFF" && !value) callback(new Error(t("user.templateRequired")));
        else callback();
      },
      trigger: "change"
    }
  ]
}));

const alreadyMember = computed(
  () =>
    !!account.value &&
    !!props.tenant &&
    account.value.companies.some(company => company.tenantId === props.tenant?.id)
);

const editVisible = ref(false);
const editFormRef = ref<FormInstance>();
const editing = ref(false);
const editErrors = ref<Record<string, string>>({});
const editForm = reactive({ nickname: "", email: "" });
const editRules: FormRules = {
  nickname: [rule(validateNickname)],
  email: [rule(validateEmail)]
};

const resetVisible = ref(false);
const resetFormRef = ref<FormInstance>();
const resetting = ref(false);
const resetErrors = ref<Record<string, string>>({});
const resetForm = reactive({ password: "" });
const resetRules: FormRules = { password: [rule(validateNewPassword)] };

const statusChanging = ref(false);

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    lookupForm.username = "";
    lookedUp.value = null;
    account.value = null;
    resetJoinForm();
    lookupFormRef.value?.clearValidate();
  }
);

function resetJoinForm(): void {
  joinForm.role = "STAFF";
  joinForm.postTemplate = "";
  joinForm.dataScope = "";
  joinFormRef.value?.clearValidate();
}

async function fetchAccount(username: string): Promise<void> {
  looking.value = true;
  try {
    account.value = await findPlatformUser(username);
    lookedUp.value = username;
  } catch (error) {
    notifyError(error);
  } finally {
    looking.value = false;
  }
}

async function lookup(): Promise<void> {
  if (!lookupFormRef.value || !(await lookupFormRef.value.validate().catch(() => false))) return;
  resetJoinForm();
  await fetchAccount(lookupForm.username.trim());
}

async function submitJoin(): Promise<void> {
  if (!props.tenant || !account.value || alreadyMember.value) return;
  if (!joinFormRef.value || !(await joinFormRef.value.validate().catch(() => false))) return;
  joining.value = true;
  try {
    await addTenantMember(
      props.tenant.id,
      joinForm.role === "SUPER_ADMIN"
        ? { userId: account.value.id, role: "SUPER_ADMIN" }
        : {
            userId: account.value.id,
            role: "STAFF",
            postTemplate: joinForm.postTemplate || undefined,
            dataScope: joinForm.dataScope || undefined
          }
    );
    ElMessage.success(t("member.joined", { name: props.tenant.name }));
    emit("joined");
    emit("update:modelValue", false);
  } catch (error) {
    notifyError(error);
  } finally {
    joining.value = false;
  }
}

function openEdit(): void {
  if (!account.value) return;
  editForm.nickname = account.value.nickname;
  editForm.email = account.value.email ?? "";
  editErrors.value = {};
  editVisible.value = true;
}

async function submitEdit(): Promise<void> {
  editErrors.value = {};
  if (!account.value || !editFormRef.value) return;
  if (!(await editFormRef.value.validate().catch(() => false))) return;
  editing.value = true;
  try {
    await updatePlatformUser(account.value.id, {
      nickname: editForm.nickname.trim(),
      email: editForm.email.trim() || null
    });
    ElMessage.success(t("member.accountSaved"));
    editVisible.value = false;
    await fetchAccount(account.value.username);
  } catch (error) {
    editErrors.value = serverFieldErrors(error, ["nickname", "email"]);
    notifyError(error);
  } finally {
    editing.value = false;
  }
}

function openReset(): void {
  resetForm.password = "";
  resetErrors.value = {};
  resetVisible.value = true;
}

async function submitReset(): Promise<void> {
  resetErrors.value = {};
  if (!account.value || !resetFormRef.value) return;
  if (!(await resetFormRef.value.validate().catch(() => false))) return;
  try {
    await ElMessageBox.confirm(t("member.confirmReset"), t("member.resetPassword"), {
      type: "warning"
    });
  } catch {
    return; // cancelled
  }
  resetting.value = true;
  try {
    await resetPlatformUserPassword(account.value.id, resetForm.password);
    ElMessage.success(t("member.resetDone"));
    resetVisible.value = false;
  } catch (error) {
    resetErrors.value = serverFieldErrors(error, ["password"]);
    notifyError(error);
  } finally {
    resetting.value = false;
  }
}

async function toggleStatus(): Promise<void> {
  if (!account.value) return;
  const target = account.value.status === 1 ? 0 : 1;
  const username = account.value.username;
  try {
    await ElMessageBox.confirm(
      target === 0
        ? t("member.confirmDisable", { username })
        : t("member.confirmEnable", { username }),
      target === 0 ? t("member.disableAccount") : t("member.enableAccount"),
      { type: "warning" }
    );
  } catch {
    return; // cancelled
  }
  statusChanging.value = true;
  try {
    await updatePlatformUserStatus(account.value.id, target);
    ElMessage.success(target === 0 ? t("member.accountDisabled") : t("member.accountEnabled"));
    await fetchAccount(username);
  } catch (error) {
    notifyError(error);
  } finally {
    statusChanging.value = false;
  }
}

function close(): void {
  emit("update:modelValue", false);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="640px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form
      ref="lookupFormRef"
      :model="lookupForm"
      :rules="lookupRules"
      inline
      @submit.prevent="lookup"
    >
      <el-form-item :label="t('common.username')" prop="username">
        <el-input
          v-model="lookupForm.username"
          maxlength="64"
          :placeholder="t('member.usernamePlaceholder')"
          data-test="lookup-username"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="looking" data-test="lookup" @click="lookup">
          {{ t("member.lookup") }}
        </el-button>
      </el-form-item>
    </el-form>

    <el-empty
      v-if="lookedUp !== null && !account"
      :image-size="64"
      :description="t('member.notFound', { username: lookedUp })"
      data-test="lookup-empty"
    />

    <template v-if="account">
      <el-descriptions :column="2" border data-test="lookup-result">
        <el-descriptions-item :label="t('common.username')">{{
          account.username
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.nickname')">{{
          account.nickname
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('common.status')">
          <el-tag :type="account.status === 1 ? 'success' : 'info'">
            {{ account.status === 1 ? t("common.enabled") : t("common.disabled") }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('member.companies')">
          <span v-if="account.companies.length === 0">{{ t("member.noCompanies") }}</span>
          <div
            v-for="item in account.companies"
            :key="item.tenantId"
            class="company-line"
            data-test="lookup-company"
          >
            {{ item.tenantName }} · {{ roleLabel(item.role) }}
            <el-tag v-if="item.status !== 1" size="small" type="info">{{
              t("common.disabled")
            }}</el-tag>
          </div>
        </el-descriptions-item>
      </el-descriptions>

      <div class="account-actions">
        <span class="account-actions__label">{{ t("member.accountActions") }}</span>
        <el-button link type="primary" data-test="account-edit" @click="openEdit">
          {{ t("member.editAccount") }}
        </el-button>
        <el-button link type="warning" data-test="account-reset" @click="openReset">
          {{ t("member.resetPassword") }}
        </el-button>
        <el-button
          link
          :type="account.status === 1 ? 'danger' : 'success'"
          :loading="statusChanging"
          data-test="account-status"
          @click="toggleStatus"
        >
          {{ account.status === 1 ? t("member.disableAccount") : t("member.enableAccount") }}
        </el-button>
      </div>

      <el-alert
        v-if="alreadyMember"
        type="info"
        :closable="false"
        :title="t('member.alreadyMember')"
        data-test="already-member"
      />
      <el-form
        v-else
        ref="joinFormRef"
        :model="joinForm"
        :rules="joinRules"
        label-width="120px"
        data-test="join-form"
        @submit.prevent
      >
        <el-form-item :label="t('member.role')">
          <el-radio-group v-model="joinForm.role" data-test="join-role">
            <el-radio v-for="role in SELECTABLE_ROLES" :key="role" :value="role">
              {{ roleLabel(role) }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="joinForm.role === 'STAFF'">
          <el-form-item :label="t('user.postTemplate')" prop="postTemplate">
            <el-select v-model="joinForm.postTemplate" data-test="join-post">
              <el-option
                v-for="post in POST_TEMPLATES"
                :key="post"
                :label="postLabel(post)"
                :value="post"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('user.dataScope')">
            <el-select
              v-model="joinForm.dataScope"
              clearable
              :placeholder="t('member.dataScopeDefault')"
              data-test="join-scope"
            >
              <el-option :label="t('scope.ALL')" value="ALL" />
              <el-option :label="t('scope.OWN')" value="OWN" />
            </el-select>
          </el-form-item>
        </template>
      </el-form>
    </template>

    <template #footer>
      <el-button @click="close">{{ t("common.cancel") }}</el-button>
      <el-button
        type="primary"
        :disabled="!account || alreadyMember"
        :loading="joining"
        data-test="join-submit"
        @click="submitJoin"
      >
        {{ t("member.submit") }}
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="editVisible"
    :title="t('member.editTitle', { username: account?.username ?? '' })"
    width="460px"
  >
    <el-form
      ref="editFormRef"
      :model="editForm"
      :rules="editRules"
      label-width="90px"
      @submit.prevent
    >
      <el-form-item :label="t('common.nickname')" prop="nickname" :error="editErrors.nickname">
        <el-input v-model="editForm.nickname" maxlength="64" data-test="account-nickname" />
      </el-form-item>
      <el-form-item :label="t('common.email')" prop="email" :error="editErrors.email">
        <el-input v-model="editForm.email" maxlength="254" data-test="account-email" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editVisible = false">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="editing" data-test="account-save" @click="submitEdit">
        {{ t("common.save") }}
      </el-button>
    </template>
  </el-dialog>

  <el-dialog
    v-model="resetVisible"
    :title="t('member.resetTitle', { username: account?.username ?? '' })"
    width="460px"
  >
    <el-form
      ref="resetFormRef"
      :model="resetForm"
      :rules="resetRules"
      label-width="90px"
      @submit.prevent
    >
      <el-form-item
        :label="t('tenantAdmin.newPassword')"
        prop="password"
        :error="resetErrors.password"
      >
        <el-input
          v-model="resetForm.password"
          type="password"
          show-password
          autocomplete="new-password"
          maxlength="72"
          data-test="account-password"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="resetVisible = false">{{ t("common.cancel") }}</el-button>
      <el-button
        type="primary"
        :loading="resetting"
        data-test="account-reset-submit"
        @click="submitReset"
      >
        {{ t("common.confirm") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.company-line {
  display: flex;
  align-items: center;
  gap: 6px;
}
.account-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  margin: 12px 0;
}
.account-actions__label {
  color: var(--el-text-color-secondary);
}
</style>
