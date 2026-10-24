<script setup lang="ts">
import { ArrowDown } from "@element-plus/icons-vue";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { logout } from "@/api/auth";
import LocaleSwitch from "@/components/LocaleSwitch.vue";
import { translate } from "@/locales";
import { useSessionStore } from "@/stores/session";
import { siteName } from "@/stores/site-config";
import { NAV_ITEMS } from "./nav";

defineOptions({ name: "AppLayout" });

const { t, locale } = useI18n();
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const signingOut = ref(false);

/** Breadcrumb of the current page, the same slot the admin header uses. */
const brandText = computed(() => `${siteName.value}${t("app.titleSuffix")}`);

const pageTitle = computed(() => {
  void locale.value;
  return route.meta.title ? translate(String(route.meta.title)) : "";
});

/** The home entry ("/") must not stay highlighted on other pages, so it compares exactly. */
function isActive(path: string): boolean {
  return path === "/" ? route.path === "/" : route.path.startsWith(path);
}

async function signOut() {
  if (signingOut.value) return;
  signingOut.value = true;
  try {
    await logout();
  } catch {
    // Local logout must finish even if the server is unavailable.
  } finally {
    session.clearSession();
    signingOut.value = false;
    await router.replace("/login");
  }
}
</script>

<template>
  <div class="shell">
    <aside class="shell__aside">
      <div class="shell__brand" :title="brandText" data-test="brand">{{ brandText }}</div>
      <nav class="shell__nav" :aria-label="t('nav.label')">
        <RouterLink
          v-for="item in NAV_ITEMS"
          :key="item.name"
          :to="item.path"
          class="shell__nav-link"
          :aria-current="isActive(item.path) ? 'page' : undefined"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ t(item.title) }}</span>
        </RouterLink>
      </nav>
    </aside>
    <div class="shell__body">
      <header class="shell__header">
        <el-breadcrumb class="shell__crumb" data-test="page-title">
          <el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div class="shell__actions">
          <span v-if="session.kol" class="shell__identity"
            >{{ session.kol.name }} · {{ session.kol.tenantName }}</span
          >
          <LocaleSwitch />
          <details class="user-menu">
            <summary>
              <span>{{ t("account.title") }}</span>
              <el-icon><ArrowDown /></el-icon>
            </summary>
            <nav class="user-menu__panel" :aria-label="t('account.title')">
              <RouterLink to="/account" class="user-menu__link">{{
                t("account.title")
              }}</RouterLink>
              <el-button
                class="user-menu__signout"
                text
                :loading="signingOut"
                :disabled="signingOut"
                @click="signOut"
                >{{ t("common.signOut") }}</el-button
              >
            </nav>
          </details>
        </div>
      </header>
      <main class="shell__main">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100%;
  min-height: 0;
}
.shell__aside {
  display: flex;
  flex: 0 0 var(--tcrm-sidebar-width);
  flex-direction: column;
  width: var(--tcrm-sidebar-width);
  background: var(--tcrm-color-card);
  border-right: 1px solid var(--tcrm-color-border);
}
.shell__brand {
  display: flex;
  align-items: center;
  box-sizing: border-box;
  height: var(--tcrm-header-height);
  padding: 0 var(--tcrm-space-4);
  border-bottom: 1px solid var(--tcrm-color-border);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--tcrm-color-text);
  font-size: var(--tcrm-font-size-title);
  font-weight: 600;
  line-height: var(--tcrm-line-height-title);
}
.shell__nav {
  display: flex;
  flex-direction: column;
  gap: 0;
  padding-top: var(--tcrm-space-1);
}
.shell__nav-link {
  display: flex;
  align-items: center;
  gap: var(--tcrm-space-2);
  height: 40px;
  margin: 0 var(--tcrm-space-2) var(--tcrm-space-1);
  padding: 0 var(--tcrm-space-4);
  border-radius: var(--tcrm-radius-control);
  color: var(--tcrm-color-text);
  text-decoration: none;
  white-space: nowrap;
}
.shell__nav-link:hover {
  background: var(--tcrm-color-muted);
}
.shell__nav-link[aria-current="page"] {
  background: var(--tcrm-color-muted);
  color: var(--tcrm-color-primary-text);
  font-weight: 600;
}
.shell__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}
.shell__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
  flex: 0 0 var(--tcrm-header-height);
  height: var(--tcrm-header-height);
  padding: 0 var(--tcrm-space-4);
  background: var(--tcrm-color-card);
  border-bottom: 1px solid var(--tcrm-color-border);
}
.shell__crumb {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.shell__actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: var(--tcrm-space-3);
}
.shell__identity {
  color: var(--tcrm-color-text-secondary);
  overflow-wrap: anywhere;
}
.user-menu {
  position: relative;
}
.user-menu summary {
  display: inline-flex;
  align-items: center;
  gap: var(--tcrm-space-1);
  min-height: 32px;
  cursor: pointer;
  list-style: none;
  color: var(--tcrm-color-text);
  white-space: nowrap;
}
.user-menu summary::-webkit-details-marker {
  display: none;
}
.user-menu__panel {
  position: absolute;
  right: 0;
  top: 100%;
  z-index: 10;
  display: grid;
  gap: var(--tcrm-space-1);
  min-width: 160px;
  margin-top: var(--tcrm-space-1);
  padding: var(--tcrm-space-2);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-control);
  box-shadow: var(--tcrm-shadow-overlay);
}
.user-menu__link {
  display: flex;
  align-items: center;
  min-height: 32px;
  padding: 0 var(--tcrm-space-2);
  border-radius: var(--tcrm-radius-control);
  color: var(--tcrm-color-text);
  text-decoration: none;
}
.user-menu__link:hover {
  background: var(--tcrm-color-muted);
}
.user-menu__signout {
  justify-content: flex-start;
  margin-left: 0;
}
.shell__main {
  flex: 1;
  min-height: 0;
  padding: var(--tcrm-space-5);
  background: var(--tcrm-color-page);
  overflow-y: auto;
  overflow-x: hidden;
}
@media (max-width: 1279px) {
  .shell__main {
    padding: var(--tcrm-space-4);
  }
}
@media (max-width: 767px) {
  .shell {
    flex-direction: column;
    height: auto;
    min-height: 100%;
  }
  .shell__aside {
    flex: none;
    width: 100%;
    border-right: none;
    border-bottom: 1px solid var(--tcrm-color-border);
  }
  .shell__brand {
    height: 44px;
  }
  .shell__nav {
    flex-direction: row;
    gap: var(--tcrm-space-1);
    padding: 0 var(--tcrm-space-3) var(--tcrm-space-2);
    overflow-x: auto;
  }
  .shell__nav-link {
    min-height: 44px;
    margin: 0;
    padding: 0 var(--tcrm-space-3);
  }
  .shell__body {
    flex: none;
  }
  .shell__header {
    flex-basis: auto;
    height: auto;
    min-height: 44px;
    flex-wrap: wrap;
    padding: var(--tcrm-space-2) var(--tcrm-space-3);
  }
  .shell__identity {
    display: none;
  }
  .shell__main {
    overflow: visible;
    padding: var(--tcrm-space-3);
  }
}
</style>
