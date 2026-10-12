<script setup lang="ts">
import { ArrowDown } from "@element-plus/icons-vue";
import { computed, onMounted } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import LocaleSwitch from "@/components/LocaleSwitch.vue";
import { translate } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import { useThemeStore } from "@/stores/theme";
import { useUserStore } from "@/stores/user";
import { buildMenu, roleLabel } from "@/utils/permission";

defineOptions({ name: "AppLayout" });

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const user = useUserStore();
const localeStore = useLocaleStore();
const theme = useThemeStore();

const menu = computed(() => {
  void localeStore.locale;
  return buildMenu(routes, user.roles, user.user?.dataScope);
});
const roleText = computed(() => (user.user ? roleLabel(user.user.role) : ""));
const pageTitle = computed(() => {
  void localeStore.locale;
  return route.meta.title ? translate(route.meta.title) : "";
});
const tenantLabel = computed(() => {
  if (user.user?.role === "PLATFORM_ADMIN") return t("common.platformScope");
  return user.user?.tenantName || "";
});
const userLabel = computed(() =>
  t("layout.user", { nickname: user.user?.nickname ?? "", role: roleText.value })
);

onMounted(() => {
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
      <div class="layout__brand">{{ t("app.title") }}</div>
      <el-menu :default-active="route.path" router class="layout__menu">
        <template v-for="item in menu" :key="item.path">
          <el-sub-menu v-if="item.children?.length" :index="item.path">
            <template #title>
              <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
              <span>{{ item.title }}</span>
            </template>
            <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path">
              <span>{{ child.title }}</span>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="item.path">
            <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="layout__header">
        <span class="layout__title">{{ pageTitle }}</span>
        <div class="layout__actions">
          <el-button
            text
            class="layout__theme"
            data-test="theme"
            :aria-label="t('theme.cycle')"
            @click="theme.cycle()"
          >
            {{ t(`theme.${theme.mode}`) }}
          </el-button>
          <LocaleSwitch />
          <el-dropdown trigger="click" @command="onCommand">
            <span class="layout__user">
              {{ userLabel }}
              <span v-if="tenantLabel" class="layout__tenant">{{ tenantLabel }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="password">{{
                  t("layout.changePassword")
                }}</el-dropdown-item>
                <el-dropdown-item command="logout" divided>{{
                  t("common.signOut")
                }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
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
  background: var(--tcrm-color-card);
  border-right: 1px solid var(--tcrm-color-border);
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
  background: var(--tcrm-color-card);
  border-bottom: 1px solid var(--tcrm-color-border);
}
.layout__title {
  font-size: 16px;
  font-weight: 600;
}
.layout__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.layout__user {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}
.layout__tenant {
  color: var(--el-text-color-secondary);
}
.layout__main {
  padding: 16px;
}
</style>
