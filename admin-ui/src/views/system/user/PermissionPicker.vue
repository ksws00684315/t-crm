<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { PermissionChoice } from "@/utils/permission";
import {
  groupPermissions,
  permissionGroupLabel,
  permissionText,
  togglePermission
} from "@/utils/permission";

defineOptions({ name: "PermissionPicker" });

const props = defineProps<{
  items: PermissionChoice[];
  modelValue: string[];
}>();

const emit = defineEmits<{
  (event: "update:modelValue", value: string[]): void;
}>();

const { t, locale } = useI18n();

const groups = computed(() => {
  void locale.value;
  const selected = new Set(props.modelValue);
  return groupPermissions(props.items).map(group => {
    const codes = group.items.map(item => item.code);
    const picked = codes.filter(code => selected.has(code)).length;
    return {
      group: group.group,
      label: permissionGroupLabel(group.group),
      codes,
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

function isChecked(value: unknown): boolean {
  return value === true;
}

function toggleOne(code: string, checked: boolean): void {
  emit("update:modelValue", togglePermission(props.modelValue, code, checked));
}

function toggleGroup(codes: readonly string[], checked: boolean): void {
  let next = [...props.modelValue];
  for (const code of codes) {
    next = togglePermission(next, code, checked);
  }
  emit("update:modelValue", next);
}
</script>

<template>
  <div class="perm-picker">
    <section v-for="group in groups" :key="group.group" class="perm-group">
      <div class="perm-group__head">
        <span class="perm-group__title">{{ group.label }}</span>
        <el-checkbox
          :model-value="group.allChecked"
          :indeterminate="group.partial"
          :data-test="`perm-all-${group.group}`"
          @change="toggleGroup(group.codes, isChecked($event))"
        >
          {{ t("user.selectAll") }}
        </el-checkbox>
      </div>
      <el-checkbox
        v-for="item in group.items"
        :key="item.code"
        :model-value="item.checked"
        :data-test="`perm-${item.code}`"
        @change="toggleOne(item.code, isChecked($event))"
      >
        <span class="perm-item">
          <span class="perm-item__name">{{ item.name }}</span>
          <span v-if="item.desc" class="perm-item__desc">{{ item.desc }}</span>
        </span>
      </el-checkbox>
    </section>
    <p class="perm-exclusive" data-test="dependencies">
      {{ t("user.permissionDependencies") }}
    </p>
    <p class="perm-exclusive" data-test="exclusive">
      <strong>{{ t("user.exclusiveTitle") }}</strong>
      {{ t("user.exclusiveItems") }}
    </p>
  </div>
</template>

<style scoped>
.perm-group {
  margin-bottom: 12px;
}

.perm-group__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.perm-group__title {
  font-weight: 600;
}

.perm-item {
  display: inline-flex;
  flex-direction: column;
  line-height: 1.3;
  white-space: normal;
}

.perm-item__desc {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.perm-group :deep(.el-checkbox) {
  display: flex;
  align-items: flex-start;
  height: auto;
  margin-right: 0;
  margin-bottom: 6px;
}

.perm-exclusive {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  line-height: 22px;
}
</style>
