<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import { listPostTemplates, resetPostTemplate, savePostTemplate } from "@/api/system/post-template";
import { getPermissionCatalog, type PermissionItemVO } from "@/api/system/user";
import type { DataScope, PostTemplate } from "@/api/types";
import DataTable, { type DataColumn } from "@/components/DataTable.vue";
import PermissionChoices from "./PermissionChoices.vue";
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
    const picked = codes.filter(code => selected.has(code)).length;
    return {
      group: group.group,
      label: permissionGroupLabel(group.group),
      codes,
      picked,
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
  if (savingFor.value || loading.value) return;
  try {
    await ElMessageBox.confirm(t("postTemplate.resetConfirm"), t("postTemplate.reset"), {
      type: "warning"
    });
  } catch {
    return;
  }
  savingFor.value = post;
  try {
    await resetPostTemplate(post);
    ElMessage.success(t("postTemplate.resetDone"));
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    savingFor.value = null;
  }
}

const mobile = ref(false);
let media: MediaQueryList | undefined;
function updateMobile() {
  mobile.value = media?.matches ?? false;
}
onMounted(() => {
  media = window.matchMedia("(max-width: 767px)");
  updateMobile();
  media.addEventListener("change", updateMobile);
  void load();
});
onBeforeUnmount(() => media?.removeEventListener("change", updateMobile));

const columns = computed<DataColumn[]>(() => [
  { key: "module", label: t("postTemplate.module"), minWidth: 170, align: "left", tooltip: false },
  {
    key: "permissions",
    label: t("postTemplate.configurablePermissions"),
    minWidth: 430,
    align: "left",
    tooltip: false,
    interactive: true
  },
  { key: "all", label: t("postTemplate.moduleAll"), minWidth: 130 }
]);
type PermissionModule = (typeof permissionModules.value)[number];
function asModule(row: object): PermissionModule {
  return row as PermissionModule;
}
</script>

<template>
  <div class="list-page post-template-page" v-loading="loading">
    <el-card class="post-template-page__card" shadow="never">
      <div class="post-template-page__head">
        <div>
          <h1 class="page-title">{{ t("postTemplate.title") }}</h1>
          <p class="post-template-page__hint" data-test="post-template-hint">
            {{ t("postTemplate.hint") }}
          </p>
        </div>
        <div class="post-template-page__actions">
          <span class="template-name">{{
            t("postTemplate.cardTitle", { post: postName(activeTemplate) })
          }}</span>
          <el-tag
            v-if="activeDraft"
            :type="isDefault[activeTemplate] ? 'info' : 'warning'"
            data-test="template-state"
          >
            {{
              isDefault[activeTemplate] ? t("postTemplate.isDefault") : t("postTemplate.customized")
            }}
          </el-tag>
          <el-button
            :disabled="!activeDraft || loading || !!savingFor"
            @click="reset(activeTemplate)"
            data-test="template-reset"
            >{{ t("postTemplate.reset") }}</el-button
          >
          <el-button
            type="primary"
            :disabled="!activeDraft || loading || !!savingFor"
            :loading="savingFor === activeTemplate"
            :data-test="`template-save-${activeTemplate}`"
            @click="save(activeTemplate)"
            >{{ t("postTemplate.save") }}</el-button
          >
        </div>
      </div>
      <el-tabs v-model="activeTemplate" class="post-template-tabs" data-test="template-tabs">
        <el-tab-pane
          v-for="post in TEMPLATES"
          :key="post"
          :name="post"
          :label="postName(post)"
          :disabled="!!savingFor"
        />
      </el-tabs>
      <template v-if="activeDraft">
        <fieldset class="scope-panel" :disabled="loading || !!savingFor">
          <legend class="sr-only">{{ t("postTemplate.dataScope") }}</legend>
          <span class="scope-label" aria-hidden="true">{{ t("postTemplate.dataScope") }}</span>
          <el-radio-group
            v-model="activeDraft.dataScope"
            :aria-label="t('postTemplate.dataScope')"
            :disabled="loading || !!savingFor"
            :data-test="`scope-${activeTemplate}`"
          >
            <el-radio v-for="option in scopeOptions" :key="option.value" :value="option.value">{{
              option.label
            }}</el-radio>
          </el-radio-group>
          <span class="scope-note">{{ t("postTemplate.unifiedScopeNote") }}</span>
        </fieldset>
        <DataTable
          v-if="!mobile"
          table-id="post-template-permission-matrix-a"
          data-test="permission-matrix"
          :rows="permissionModules"
          :columns="columns"
          :total="permissionModules.length"
          row-key="group"
          plain
          :paged="false"
          :fixed-columns="false"
        >
          <template #cell-module="{ row }">
            <div class="permission-module">
              <strong>{{ asModule(row).label }}</strong>
              <span class="permission-count" :data-test="`module-count-${asModule(row).group}`">{{
                t("postTemplate.selectedCount", {
                  selected: asModule(row).picked,
                  total: asModule(row).codes.length
                })
              }}</span>
            </div>
          </template>
          <template #cell-permissions="{ row }">
            <PermissionChoices
              v-if="asModule(row).items"
              :items="asModule(row).items"
              :disabled="loading || !!savingFor"
              @toggle="toggleOne"
            />
          </template>
          <template #cell-all="{ row }">
            <el-checkbox
              :model-value="asModule(row).allChecked"
              :indeterminate="asModule(row).partial"
              :disabled="loading || !!savingFor"
              :aria-label="t('postTemplate.selectModule', { module: asModule(row).label })"
              :data-test="`template-module-all-${asModule(row).group}`"
              @change="toggleGroup(asModule(row).codes, isChecked($event))"
              ><span class="sr-only">{{
                t("postTemplate.selectModule", { module: asModule(row).label })
              }}</span></el-checkbox
            >
          </template>
        </DataTable>
        <div v-else class="permission-cards" data-test="permission-cards">
          <el-empty v-if="permissionModules.length === 0" :description="t('table.empty')" />
          <section v-for="module in permissionModules" :key="module.group" class="permission-card">
            <header class="permission-card__head">
              <div class="permission-module">
                <strong>{{ module.label }}</strong
                ><span class="permission-count" :data-test="`module-count-${module.group}`">{{
                  t("postTemplate.selectedCount", {
                    selected: module.picked,
                    total: module.codes.length
                  })
                }}</span>
              </div>
              <el-checkbox
                :model-value="module.allChecked"
                :indeterminate="module.partial"
                :disabled="loading || !!savingFor"
                :aria-label="t('postTemplate.selectModule', { module: module.label })"
                :data-test="`template-module-all-${module.group}`"
                @change="toggleGroup(module.codes, isChecked($event))"
                >{{ t("user.selectAll") }}</el-checkbox
              >
            </header>
            <PermissionChoices
              :items="module.items"
              :disabled="loading || !!savingFor"
              @toggle="toggleOne"
            />
          </section>
        </div>
        <div class="permission-note">
          <p>{{ t("user.permissionDependencies") }}</p>
          <p>
            <strong>{{ t("user.exclusiveTitle") }}</strong> {{ t("user.exclusiveItems") }}
          </p>
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.post-template-page__card {
  min-width: 0;
}
.post-template-page__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--tcrm-space-4);
  margin-bottom: var(--tcrm-space-4);
}
.page-title {
  margin: 0;
  font-size: var(--tcrm-font-size-title);
  line-height: var(--tcrm-line-height-title);
  font-weight: 600;
}
.post-template-page__hint {
  margin: var(--tcrm-space-2) 0 0;
  color: var(--tcrm-color-text-secondary);
}
.post-template-page__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.post-template-page__actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.template-name {
  color: var(--tcrm-color-text-secondary);
}
.scope-panel {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--tcrm-space-3) var(--tcrm-space-5);
  min-width: 0;
  margin: var(--tcrm-space-4) 0 var(--tcrm-space-5);
  padding: var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-control);
  background: var(--tcrm-color-table-header);
}
.scope-label {
  font-weight: 500;
}
.scope-note {
  margin-left: auto;
  color: var(--tcrm-color-text-secondary);
}
.permission-module {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-1);
}
.permission-module strong {
  font-weight: 500;
}
.permission-count {
  font-size: var(--tcrm-font-size-help);
  line-height: var(--tcrm-line-height-help);
  color: var(--tcrm-color-text-secondary);
}
:deep(.el-table td.el-table__cell) {
  padding-block: var(--tcrm-space-4);
}
.permission-note {
  margin-top: var(--tcrm-space-4);
  padding: var(--tcrm-space-3) var(--tcrm-space-4);
  border-radius: var(--tcrm-radius-control);
  background: var(--tcrm-color-table-header);
  color: var(--tcrm-color-text-secondary);
}
.permission-note p {
  margin: 0;
}
.permission-note p + p {
  margin-top: var(--tcrm-space-1);
}
.permission-cards {
  display: grid;
  gap: var(--tcrm-space-3);
}
.permission-card {
  padding: var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-control);
}
.permission-card__head {
  display: flex;
  justify-content: space-between;
  gap: var(--tcrm-space-2);
  margin-bottom: var(--tcrm-space-3);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
@media (max-width: 1279px) {
  .scope-note {
    margin-left: 0;
  }
}
@media (max-width: 767px) {
  .permission-card__head :deep(.el-checkbox) {
    min-height: var(--tcrm-control-height-mobile);
  }
  .post-template-page__actions {
    justify-content: flex-start;
  }
  .scope-panel {
    gap: var(--tcrm-space-2);
  }
  .scope-label,
  .scope-note {
    flex-basis: 100%;
  }
  .scope-panel :deep(.el-radio-group) {
    display: flex;
    flex-wrap: wrap;
    gap: var(--tcrm-space-2);
  }
  .scope-panel :deep(.el-radio) {
    margin-right: var(--tcrm-space-2);
  }
}
</style>
