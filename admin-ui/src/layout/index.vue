<script setup lang="ts">
import { ArrowDown, Expand, Fold, FullScreen, Refresh, Search } from "@element-plus/icons-vue";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import LocaleSwitch from "@/components/LocaleSwitch.vue";
import { translate } from "@/locales";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import { useMenuSearchStore } from "@/stores/menu-search";
import { useReauthStore } from "@/stores/reauth";
import { useTabsStore } from "@/stores/tabs";
import { useThemeStore } from "@/stores/theme";
import { useUserStore } from "@/stores/user";
import { flattenMenu, rememberRecentMenu } from "@/utils/menu-search";
import { buildMenu, canAccessRoute, roleLabel } from "@/utils/permission";
import { watermarkInk, watermarkText } from "@/utils/watermark";
import { affixFor } from "@/utils/tabs";
import {
  isMacPlatform,
  loadSidebarCollapsed,
  saveSidebarCollapsed,
  subscribeNarrow
} from "@/utils/sidebar";
import MenuSearch from "./MenuSearch.vue";
import ReauthDialog from "./ReauthDialog.vue";
import SideMenu from "./SideMenu.vue";
import TabBar from "./TabBar.vue";

defineOptions({ name: "AppLayout" });

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const user = useUserStore();
const localeStore = useLocaleStore();
const theme = useThemeStore();
const menuSearch = useMenuSearchStore();
const reauth = useReauthStore();
const tabs = useTabsStore();

const collapsed = ref(loadSidebarCollapsed());
const narrow = ref(false);
const drawerOpen = ref(false);
const fullscreenOn = ref(false);
const fullscreenEnabled = ref(
  typeof document !== "undefined" && document.fullscreenEnabled === true
);
const mac = isMacPlatform();

const menu = computed(() => {
  void localeStore.locale;
  return buildMenu(routes, user.roles, user.user?.dataScope);
});
const roleText = computed(() => (user.user ? roleLabel(user.user.role) : ""));
const tenantLabel = computed(() => {
  if (user.user?.role === "PLATFORM_ADMIN") return t("common.platformScope");
  return user.user?.tenantName || "";
});
const userLabel = computed(() =>
  t("layout.user", { nickname: user.user?.nickname ?? "", role: roleText.value })
);
const crumbs = computed(() => {
  void localeStore.locale;
  return route.matched
    .filter(record => Boolean(record.meta.title) && record.path !== "/")
    .map(record => ({
      path: record.path,
      title: translate(String(record.meta.title))
    }));
});
const asideWidth = computed(() =>
  collapsed.value ? "var(--tcrm-sidebar-collapsed)" : "var(--tcrm-sidebar-width)"
);
const watermark = ref(
  watermarkText(user.user?.nickname ?? "", user.user?.username ?? "", new Date())
);
const watermarkColor = ref(watermarkInk("#15181d"));

function refreshWatermark(): void {
  watermark.value = watermarkText(user.user?.nickname ?? "", user.user?.username ?? "", new Date());
}

function syncWatermarkColor(): void {
  const hex = getComputedStyle(document.documentElement)
    .getPropertyValue("--tcrm-color-text")
    .trim();
  watermarkColor.value = watermarkInk(hex || "#15181d");
}

const unsubscribeNarrow = subscribeNarrow(matches => {
  narrow.value = matches;
  if (!matches) drawerOpen.value = false;
});

function toggleSidebar(): void {
  if (narrow.value) {
    drawerOpen.value = !drawerOpen.value;
    return;
  }
  collapsed.value = !collapsed.value;
  saveSidebarCollapsed(collapsed.value);
}

function refreshPage(): void {
  tabs.refresh(route.path);
}

function toggleFullscreen(): void {
  if (!document.fullscreenEnabled) return;
  if (document.fullscreenElement) {
    void document.exitFullscreen();
  } else {
    void document.documentElement.requestFullscreen();
  }
}

function onFullscreenChange(): void {
  fullscreenOn.value = document.fullscreenElement != null;
}

async function onCommand(command: string) {
  if (command === "password") {
    await router.push("/account/password");
  } else if (command === "logout") {
    reauth.close();
    tabs.reset();
    await user.logout();
    await router.replace("/login");
  }
}

tabs.restore(
  affixFor(user.roles.includes("PLATFORM_ADMIN")),
  path => {
    const resolved = router.resolve(path);
    return canAccessRoute(resolved.meta, user.roles, user.user?.dataScope);
  },
  user.user?.id ?? null
);

watch(
  () => route.path,
  () => {
    refreshWatermark();
    const componentName = route.meta.componentName;
    const titleKey = route.meta.title;
    if (!componentName || !titleKey || route.meta.noTab) return;
    const affix = affixFor(user.roles.includes("PLATFORM_ADMIN"));
    tabs.open({
      path: route.path,
      titleKey,
      affix: route.path === affix.path,
      componentName
    });
    const userId = user.user?.id;
    if (userId && flattenMenu(menu.value).some(entry => entry.path === route.path)) {
      rememberRecentMenu(userId, route.path);
    }
  },
  { immediate: true }
);

// The theme class is applied before this runs, so the canvas color can be read immediately.
watch(
  () => theme.resolved,
  () => {
    syncWatermarkColor();
  },
  { flush: "sync" }
);

onMounted(() => {
  user.refreshProfile().catch(() => undefined);
  document.addEventListener("fullscreenchange", onFullscreenChange);
  syncWatermarkColor();
});

onBeforeUnmount(() => {
  document.removeEventListener("fullscreenchange", onFullscreenChange);
  unsubscribeNarrow();
});
</script>

<template>
  <el-container
    class="layout"
    :class="{ 'is-collapsed': collapsed && !narrow, 'is-narrow': narrow }"
  >
    <el-aside v-if="!narrow" :width="asideWidth" class="layout__aside">
      <SideMenu
        :items="menu"
        :active="route.path"
        :collapse="collapsed"
        @select="drawerOpen = false"
      />
    </el-aside>
    <el-drawer
      v-else
      v-model="drawerOpen"
      direction="ltr"
      :size="224"
      :with-header="false"
      class="layout__drawer"
      data-test="sidebar-drawer"
    >
      <SideMenu :items="menu" :active="route.path" :collapse="false" @select="drawerOpen = false" />
    </el-drawer>
    <el-container>
      <el-header class="layout__header" height="56px">
        <div class="layout__header-start">
          <el-button
            text
            data-test="sidebar-toggle"
            :aria-label="collapsed || narrow ? t('layout.expand') : t('layout.collapse')"
            @click="toggleSidebar"
          >
            <el-icon><Expand v-if="collapsed || narrow" /><Fold v-else /></el-icon>
          </el-button>
          <el-breadcrumb>
            <el-breadcrumb-item
              v-for="(crumb, index) in crumbs"
              :key="crumb.path"
              :to="index < crumbs.length - 1 ? crumb.path : undefined"
            >
              {{ crumb.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="layout__actions">
          <el-button data-test="menu-search" @click="menuSearch.open()">
            <el-icon><Search /></el-icon>
            <span class="layout__label">{{ t("layout.searchMenu") }}</span>
            <kbd class="layout__kbd">{{
              mac ? t("layout.shortcutMac") : t("layout.shortcutOther")
            }}</kbd>
          </el-button>
          <el-button
            text
            data-test="refresh-page"
            :aria-label="t('layout.refresh')"
            @click="refreshPage"
          >
            <el-icon><Refresh /></el-icon>
          </el-button>
          <el-button
            v-if="fullscreenEnabled"
            text
            data-test="fullscreen"
            :aria-label="fullscreenOn ? t('layout.exitFullscreen') : t('layout.fullscreen')"
            @click="toggleFullscreen"
          >
            <el-icon><FullScreen /></el-icon>
          </el-button>
          <el-button
            text
            class="layout__theme"
            data-test="theme"
            :aria-label="t('theme.cycle')"
            @click="theme.cycle()"
          >
            <span class="layout__label">{{ t(`theme.${theme.mode}`) }}</span>
          </el-button>
          <LocaleSwitch />
          <el-dropdown trigger="click" @command="onCommand">
            <span class="layout__user">
              <span class="layout__label">{{ userLabel }}</span>
              <span v-if="tenantLabel" class="layout__tenant layout__label">{{ tenantLabel }}</span>
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
      <TabBar />
      <el-main class="layout__main">
        <el-watermark
          class="layout__watermark"
          :content="watermark"
          :rotate="-20"
          :gap="[140, 120]"
          :font="{ color: watermarkColor, fontSize: 14 }"
          data-test="watermark"
        >
          <RouterView v-slot="{ Component }">
            <Transition name="tcrm-page" mode="out-in">
              <KeepAlive :include="tabs.include">
                <component :is="Component" v-if="Component" :key="tabs.keyFor(route.path)" />
              </KeepAlive>
            </Transition>
          </RouterView>
        </el-watermark>
      </el-main>
    </el-container>
    <MenuSearch />
    <ReauthDialog />
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
  --layout-aside-width: var(--tcrm-sidebar-width);
}
.layout.is-collapsed {
  --layout-aside-width: var(--tcrm-sidebar-collapsed);
}
.layout.is-narrow {
  --layout-aside-width: 0px;
}
.layout__aside {
  background: var(--tcrm-color-card);
  border-right: 1px solid var(--tcrm-color-border);
  overflow: hidden;
}
.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
  background: var(--tcrm-color-card);
  border-bottom: 1px solid var(--tcrm-color-border);
  padding: 0 var(--tcrm-space-4);
}
.layout__header-start {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  min-width: 0;
}
.layout__actions {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
}
.layout__kbd {
  margin-left: var(--tcrm-space-2);
  padding: 0 6px;
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-tag);
  font-family: var(--tcrm-font-mono);
  font-size: 12px;
  line-height: 18px;
  color: var(--tcrm-color-text-secondary);
  background: var(--tcrm-color-muted);
}
.layout__header-start :deep(.el-breadcrumb) {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.layout__header-start :deep(.el-breadcrumb__item) {
  float: none;
  display: inline;
  white-space: nowrap;
}
.layout__actions,
.layout__user,
.layout__tenant {
  white-space: nowrap;
  flex-shrink: 0;
}
.layout__user {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: var(--tcrm-color-text);
}
.layout__tenant {
  color: var(--tcrm-color-text-secondary);
}
.layout__main {
  display: flex;
  flex-direction: column;
  background: var(--tcrm-color-page);
  padding: var(--tcrm-space-4);
  min-height: 0;
  overflow: hidden;
}
.layout__watermark {
  flex: 1;
  min-height: 0;
  height: 100%;
}
.layout__watermark :deep(> div) {
  height: 100%;
  min-height: 0;
}
@media (max-width: 991px) {
  .layout__label,
  .layout__kbd {
    display: none;
  }
}
</style>

<style>
.tcrm-page-enter-active,
.tcrm-page-leave-active {
  transition: opacity 150ms;
}
.tcrm-page-enter-from,
.tcrm-page-leave-to {
  opacity: 0;
}
@media (prefers-reduced-motion: reduce) {
  .tcrm-page-enter-active,
  .tcrm-page-leave-active {
    transition: none;
  }
}
</style>
