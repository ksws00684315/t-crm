<script setup lang="ts">
import { ArrowDown } from "@element-plus/icons-vue";
import { computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { routes } from "@/router/routes";
import { useUserStore } from "@/stores/user";
import { buildMenu, ROLE_LABELS } from "@/utils/permission";

defineOptions({ name: "AppLayout" });

const route = useRoute();
const router = useRouter();
const user = useUserStore();

const menu = computed(() => buildMenu(routes, user.roles));
const roleLabel = computed(() => (user.user ? ROLE_LABELS[user.user.role] : ""));

onMounted(() => {
  // Pick up role changes or a disabled account made since the last visit. A 401 redirects by itself.
  user.refreshProfile().catch(() => undefined);
});

async function onCommand(command: string) {
  if (command === "password") {
    await router.push("/account/password");
  } else if (command === "logout") {
    await user.logout();
    await router.replace("/login");
  }
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="208px" class="layout__aside">
      <div class="layout__brand">T-CRM 管理端</div>
      <el-menu :default-active="route.path" router class="layout__menu">
        <el-menu-item v-for="item in menu" :key="item.path" :index="item.path">
          <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="layout__header">
        <span class="layout__title">{{ route.meta.title }}</span>
        <el-dropdown trigger="click" @command="onCommand">
          <span class="layout__user">
            {{ user.user?.nickname }}（{{ roleLabel }}）
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="layout__main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}
.layout__aside {
  background: #fff;
  border-right: 1px solid var(--el-border-color-light);
}
.layout__brand {
  height: 56px;
  line-height: 56px;
  padding: 0 20px;
  font-size: 18px;
  font-weight: 600;
}
.layout__menu {
  border-right: none;
}
.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-light);
}
.layout__title {
  font-size: 16px;
  font-weight: 600;
}
.layout__user {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}
.layout__main {
  padding: 16px;
}
</style>
