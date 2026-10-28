<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listPostTemplates, resetPostTemplate, savePostTemplate } from "@/api/system/post-template";
import { getPermissionCatalog, type PermissionItemVO } from "@/api/system/user";
import type { DataScope, PostTemplate } from "@/api/types";
import { notifyError } from "@/utils/notify";
import {
  groupPermissions,
  permissionGroupLabel,
  permissionText,
  togglePermission
} from "@/utils/permission";

defineOptions({ name: "SystemPostTemplatePage" });

/**
 * Permissions (task 32 §2.3, contract 33 §3.4): the post templates of this company. Saving
 * only changes what newly created accounts start with; existing accounts keep their grants.
 */
const { t, locale } = useI18n();

const TEMPLATES: PostTemplate[] = ["BD", "OPERATOR", "FINANCE"];

const loading = ref(false);
const catalog = ref<PermissionItemVO[]>([]);
/** Local working copies; saving writes one card at a time. */
const drafts = reactive<Record<string, { dataScope: DataScope; permissions: string[] }>>({});
const savingFor = ref<PostTemplate | null>(null);
const isDefault = reactive<Record<string, boolean>>({});
const activeTemplate = ref<PostTemplate>("BD");

const activeDraft = computed(() => drafts[activeTemplate.value]);
const permissionModules = computed(() => {
  void locale.value;
  const selected = new Set(activeDraft.value?.permissions ?? []);
  return groupPermissions(catalog.value).map(group => {
    const codes = group.items.map(item => item.code);
    const readCodes = codes.filter(
      code =>
        code.endsWith(":view") ||
        ["dashboard:shop", "dashboard:team", "dashboard:kol"].includes(code)
    );
    const picked = codes.filter(code => selected.has(code)).length;
    return {
      group: group.group,
      label: permissionGroupLabel(group.group),
      codes,
      readCodes,
      level:
        picked === 0
          ? "HIDDEN"
          : readCodes.length > 0 &&
              picked === readCodes.length &&
              readCodes.every(code => selected.has(code))
            ? "READ"
            : picked === codes.length
              ? "MANAGE"
              : "CUSTOM",
      allChecked: codes.length > 0 && picked === codes.length,
      partial: picked > 0 && picked < codes.length,
      items: group.items.map(item => ({
        code: item.code,
        name: permissionText(item.code, "name"),
        desc: permissionText(item.code, "desc"),
        checked: selected.has(item.code)
      }))
    };
  });
});

const scopeOptions = computed(() => [
  { label: t("postTemplate.scopeAll"), value: "ALL" },
  { label: t("postTemplate.scopeOwn"), value: "OWN" }
]);

async function load() {
  loading.value = true;
  try {
    const [templates, permissionCatalog] = await Promise.all([
      listPostTemplates(),
      getPermissionCatalog()
    ]);
    catalog.value = permissionCatalog.permissions;
    for (const template of templates) {
      drafts[template.postTemplate] = {
        dataScope: template.dataScope,
        permissions: [...template.permissions]
      };
      isDefault[template.postTemplate] = template.isDefault;
    }
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function postName(post: PostTemplate): string {
  return t(`post.${post}`);
}

function isChecked(value: unknown): boolean {
  return value === true;
}

function toggleOne(code: string, checked: boolean): void {
  const draft = activeDraft.value;
  if (!draft) return;
  draft.permissions = togglePermission(draft.permissions, code, checked);
}

function toggleGroup(codes: readonly string[], checked: boolean): void {
  const draft = activeDraft.value;
  if (!draft) return;
  let next = [...draft.permissions];
  for (const code of codes) next = togglePermission(next, code, checked);
  draft.permissions = next;
}

function setModuleLevel(
  module: { codes: string[]; readCodes: string[] },
  level: string | number | boolean | undefined
): void {
  if (!["HIDDEN", "READ", "MANAGE"].includes(String(level))) return;
  const draft = activeDraft.value;
  if (!draft) return;
  let next = [...draft.permissions];
  for (const code of module.codes) next = togglePermission(next, code, false);
  for (const code of level === "MANAGE" ? module.codes : level === "READ" ? module.readCodes : []) {
    next = togglePermission(next, code, true);
  }
  draft.permissions = next;
}

async function save(post: PostTemplate) {
  const draft = drafts[post];
  if (!draft || savingFor.value) return;
  if (draft.permissions.length === 0) {
    ElMessage.warning(t("user.permissionRequired"));
    return;
  }
  savingFor.value = post;
  try {
    await savePostTemplate(post, { dataScope: draft.dataScope, permissions: draft.permissions });
    ElMessage.success(t("postTemplate.saved"));
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    savingFor.value = null;
  }
}

async function reset(post: PostTemplate) {
  try {
    await ElMessageBox.confirm(t("postTemplate.resetConfirm"), t("postTemplate.reset"), {
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    await resetPostTemplate(post);
    ElMessage.success(t("postTemplate.resetDone"));
    await load();
  } catch (error) {
    notifyError(error);
  }
}

onMounted(load);
</script>

<template>
  <div class="list-page post-template-page" v-loading="loading">
    <el-alert
      class="post-template-page__hint"
      type="info"
      :closable="false"
      :title="t('postTemplate.hint')"
      data-test="post-template-hint"
    />
    <el-tabs v-model="activeTemplate" class="post-template-tabs" data-test="template-tabs">
      <el-tab-pane v-for="post in TEMPLATES" :key="post" :name="post" :label="postName(post)" />
    </el-tabs>
    <el-card v-if="activeDraft" class="post-template-page__card" shadow="never">
      <template #header>
        <div class="post-template-page__head">
          <span>{{ t("postTemplate.cardTitle", { post: postName(activeTemplate) }) }}</span>
          <el-tag :type="isDefault[activeTemplate] ? 'info' : 'warning'" data-test="template-state">
            {{
              isDefault[activeTemplate] ? t("postTemplate.isDefault") : t("postTemplate.customized")
            }}
          </el-tag>
          <div class="post-template-page__actions">
            <el-button @click="reset(activeTemplate)" data-test="template-reset">{{
              t("postTemplate.reset")
            }}</el-button>
            <el-button
              type="primary"
              :loading="savingFor === activeTemplate"
              :data-test="`template-save-${activeTemplate}`"
              @click="save(activeTemplate)"
              >{{ t("postTemplate.save") }}</el-button
            >
          </div>
        </div>
      </template>
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('postTemplate.dataScope')">
          <el-radio-group v-model="activeDraft.dataScope" :data-test="`scope-${activeTemplate}`">
            <el-radio-button
              v-for="option in scopeOptions"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('postTemplate.permissions')">
          <p class="permission-note">{{ t("postTemplate.unifiedScopeNote") }}</p>
          <el-table :data="permissionModules" data-test="permission-matrix">
            <el-table-column :label="t('postTemplate.module')" min-width="180">
              <template #default="{ row }">
                <div class="post-template-page__module">
                  <strong>{{ row.label }}</strong>
                  <el-checkbox
                    :model-value="row.allChecked"
                    :indeterminate="row.partial"
                    :data-test="`template-module-all-${row.group}`"
                    @change="toggleGroup(row.codes, isChecked($event))"
                  >
                    {{ t("user.selectAll") }}
                  </el-checkbox>
                </div>
              </template>
            </el-table-column>
            <el-table-column :label="t('postTemplate.accessLevel')" min-width="390">
              <template #default="{ row }">
                <div class="post-template-page__levels">
                  <el-radio-group
                    :model-value="row.level"
                    :data-test="`module-level-${row.group}`"
                    @update:model-value="setModuleLevel(row, $event)"
                  >
                    <el-radio-button value="HIDDEN">{{ t("postTemplate.hidden") }}</el-radio-button>
                    <el-radio-button value="READ" :disabled="!row.readCodes.length">{{
                      t("postTemplate.readOnly")
                    }}</el-radio-button>
                    <el-radio-button
                      value="MANAGE"
                      :disabled="row.codes.length === row.readCodes.length"
                      >{{ t("postTemplate.manage") }}</el-radio-button
                    >
                  </el-radio-group>
                  <el-tag v-if="row.level === 'CUSTOM'" type="info">{{
                    t("postTemplate.customized")
                  }}</el-tag>
                </div>
                <details class="permission-details">
                  <summary>{{ t("postTemplate.finePermissions") }}</summary>
                  <div class="post-template-page__permission-list">
                    <el-checkbox
                      v-for="item in row.items"
                      :key="item.code"
                      :model-value="item.checked"
                      :data-test="`template-permission-${item.code}`"
                      @change="toggleOne(item.code, isChecked($event))"
                    >
                      <span class="permission-name">{{ item.name }}</span>
                      <span v-if="item.desc" class="permission-description">{{ item.desc }}</span>
                    </el-checkbox>
                  </div>
                </details>
              </template>
            </el-table-column>
            <el-table-column :label="t('postTemplate.dataScope')" min-width="180" align="center">
              <template #default="{ row }">
                <span :data-test="`module-scope-${row.group}`">{{
                  activeDraft.dataScope === "OWN"
                    ? t("postTemplate.scopeOwn")
                    : t("postTemplate.scopeAll")
                }}</span>
              </template>
            </el-table-column>
          </el-table>
          <p class="permission-note">{{ t("user.permissionDependencies") }}</p>
          <p class="permission-note">
            <strong>{{ t("user.exclusiveTitle") }}</strong> {{ t("user.exclusiveItems") }}
          </p>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.post-template-page__hint {
  margin-bottom: var(--tcrm-space-3);
}
.post-template-tabs {
  margin-bottom: var(--tcrm-space-3);
}
.post-template-page__card {
  min-width: 0;
}
.post-template-page__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--tcrm-space-3);
}
.post-template-page__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--tcrm-space-2);
  margin-left: auto;
}
.post-template-page__levels {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.permission-details > summary {
  cursor: pointer;
  color: var(--tcrm-color-primary);
  margin-top: var(--tcrm-space-2);
}
.permission-details .post-template-page__permission-list {
  padding-top: var(--tcrm-space-3);
}
.post-template-page__card :deep(.el-form-item__content) {
  display: block;
}
.post-template-page__card :deep(.el-table td.el-table__cell) {
  padding-block: var(--tcrm-space-4);
}
.post-template-page__module {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--tcrm-space-2);
}
.post-template-page__permission-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2) var(--tcrm-space-4);
}
.post-template-page__permission-list :deep(.el-checkbox) {
  height: auto;
  margin-right: 0;
}
.permission-name {
  font-weight: 500;
}
.permission-description {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: var(--tcrm-font-size-help);
}
.permission-note {
  margin: 8px 0 0;
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
}
</style>
