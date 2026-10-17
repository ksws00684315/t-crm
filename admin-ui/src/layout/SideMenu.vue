<script setup lang="ts">
import { useI18n } from "vue-i18n";
import type { MenuItem } from "@/utils/permission";

defineOptions({ name: "SideMenu" });

defineProps<{
  items: MenuItem[];
  active: string;
  collapse: boolean;
}>();

const emit = defineEmits<{ select: [] }>();
const { t } = useI18n();
</script>

<template>
  <div class="side" :class="{ 'is-collapsed': collapse }">
    <div class="side__brand">
      <span v-if="!collapse">{{ t("app.title") }}</span>
    </div>
    <el-menu
      :default-active="active"
      :collapse="collapse"
      :collapse-transition="false"
      router
      class="side__menu"
      @select="emit('select')"
    >
      <template v-for="item in items" :key="item.path">
        <el-sub-menu v-if="item.children?.length" :index="item.path">
          <template #title>
            <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
          </template>
          <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path">
            <span>{{ child.title }}</span>
            <el-badge
              v-if="child.badge"
              class="side__badge"
              :value="child.badge"
              :max="99"
              data-test="menu-badge"
            />
          </el-menu-item>
        </el-sub-menu>
        <el-menu-item v-else :index="item.path">
          <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
          <el-badge
            v-if="item.badge"
            class="side__badge"
            :value="item.badge"
            :max="99"
            data-test="menu-badge"
          />
        </el-menu-item>
      </template>
    </el-menu>
  </div>
</template>

<style scoped>
.side {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--tcrm-color-card);
}
.side__brand {
  height: var(--tcrm-header-height);
  display: flex;
  align-items: center;
  padding: 0 var(--tcrm-space-4);
  font-size: 16px;
  font-weight: 600;
  color: var(--tcrm-color-text);
}
.is-collapsed .side__brand {
  padding: 0;
}
.side__menu {
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: var(--tcrm-color-muted);
  --el-menu-active-color: var(--tcrm-color-primary-text);
  --el-menu-text-color: var(--tcrm-color-text);
  --el-menu-item-height: 40px;
}
.side:not(.is-collapsed) .side__menu :deep(.el-menu-item),
.side:not(.is-collapsed) .side__menu :deep(.el-sub-menu__title) {
  margin: 2px var(--tcrm-space-2);
  width: calc(100% - 16px);
  border-radius: var(--tcrm-radius-control);
}
.side__menu :deep(.el-sub-menu__title) {
  color: var(--tcrm-color-text-secondary);
}
.side__menu :deep(.el-menu-item.is-active) {
  background: var(--tcrm-color-muted);
  color: var(--tcrm-color-primary-text);
  font-weight: 600;
}
.side__badge {
  margin-left: auto;
  transform: scale(0.9);
  transform-origin: right center;
}
.is-collapsed .side__badge {
  display: none;
}
</style>
