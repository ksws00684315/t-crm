<script setup lang="ts">
import { Close, MoreFilled } from "@element-plus/icons-vue";
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { useLocaleStore } from "@/stores/locale";
import { useTabsStore } from "@/stores/tabs";
import { translate } from "@/locales";

defineOptions({ name: "TabBar" });

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const tabs = useTabsStore();
const localeStore = useLocaleStore();
const scroller = ref<HTMLElement | null>(null);
const menu = ref<{ x: number; y: number; path: string } | null>(null);

const titles = computed(() => {
  void localeStore.locale;
  return new Map(tabs.tabs.map(tab => [tab.path, translate(tab.titleKey)]));
});

function title(path: string): string {
  return titles.value.get(path) ?? "";
}

async function follow(next: string, closedPath: string): Promise<void> {
  menu.value = null;
  if (route.path === closedPath && next && next !== route.path) await router.push(next);
}

function onClose(path: string): void {
  void follow(tabs.close(path), path);
}

function onAux(event: MouseEvent, path: string, affix: boolean): void {
  if (event.button !== 1 || affix) return;
  event.preventDefault();
  onClose(path);
}

function openMenu(event: MouseEvent, path: string): void {
  menu.value = { x: event.clientX, y: event.clientY, path };
}

function run(action: "refresh" | "others" | "left" | "right" | "all"): void {
  const path = menu.value?.path;
  if (!path) return;
  menu.value = null;
  if (action === "refresh") {
    tabs.refresh(path);
    return;
  }
  const next =
    action === "others"
      ? tabs.closeOtherTabs(path)
      : action === "left"
        ? tabs.closeTabsLeft(path)
        : action === "right"
          ? tabs.closeTabsRight(path)
          : tabs.closeEveryTab();
  if (route.path !== next) void router.push(next);
}

function onWheel(event: WheelEvent): void {
  if (!scroller.value) return;
  if (Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return;
  if (event.cancelable) event.preventDefault();
  scroller.value.scrollLeft += event.deltaY;
}

function onDocumentClick(): void {
  menu.value = null;
}

watch(
  () => route.path,
  async () => {
    await nextTick();
    const current = scroller.value?.querySelector(".is-active");
    if (current && typeof current.scrollIntoView === "function") {
      current.scrollIntoView({ inline: "nearest", block: "nearest" });
    }
  }
);

document.addEventListener("click", onDocumentClick);
onBeforeUnmount(() => document.removeEventListener("click", onDocumentClick));
</script>

<template>
  <div class="tabs">
    <div ref="scroller" class="tabs__scroll" data-test="tab-scroll" @wheel="onWheel">
      <div
        v-for="tab in tabs.tabs"
        :key="tab.path"
        class="tabs__item"
        :class="{ 'is-active': tab.path === route.path }"
        data-test="tab"
        :data-path="tab.path"
        @click="router.push(tab.path)"
        @auxclick="onAux($event, tab.path, tab.affix)"
        @contextmenu.prevent="openMenu($event, tab.path)"
      >
        <span>{{ title(tab.path) }}</span>
        <button
          v-if="!tab.affix"
          type="button"
          class="tabs__close"
          data-test="tab-close"
          :aria-label="t('tabs.close')"
          @click.stop="onClose(tab.path)"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </div>
    <button
      type="button"
      class="tabs__more"
      data-test="tabs-more"
      :aria-label="t('tabs.more')"
      @click.stop="openMenu($event, route.path)"
    >
      <el-icon><MoreFilled /></el-icon>
    </button>
    <div
      v-if="menu"
      class="tabs__menu"
      data-test="tab-menu"
      :style="{ left: `${menu.x}px`, top: `${menu.y}px` }"
      @click.stop
    >
      <button type="button" @click="run('refresh')">{{ t("tabs.refresh") }}</button>
      <button type="button" @click="run('others')">{{ t("tabs.closeOthers") }}</button>
      <button type="button" @click="run('left')">{{ t("tabs.closeLeft") }}</button>
      <button type="button" @click="run('right')">{{ t("tabs.closeRight") }}</button>
      <button type="button" @click="run('all')">{{ t("tabs.closeAll") }}</button>
    </div>
  </div>
</template>

<style scoped>
.tabs {
  display: flex;
  align-items: stretch;
  height: 40px;
  background: var(--tcrm-color-card);
  border-bottom: 1px solid var(--tcrm-color-border);
}
.tabs__scroll {
  display: flex;
  flex: 1;
  min-width: 0;
  gap: var(--tcrm-space-1);
  overflow-x: auto;
  padding: var(--tcrm-space-1) var(--tcrm-space-2) 0;
}
.tabs__item {
  display: inline-flex;
  align-items: center;
  gap: var(--tcrm-space-1);
  height: 32px;
  padding: 0 var(--tcrm-space-2);
  border: 1px solid transparent;
  border-bottom: none;
  border-radius: var(--tcrm-radius-control) var(--tcrm-radius-control) 0 0;
  background: transparent;
  color: var(--tcrm-color-text-secondary);
  cursor: pointer;
  white-space: nowrap;
}
.tabs__item.is-active {
  color: var(--tcrm-color-text);
  background: var(--tcrm-color-page);
  border-color: var(--tcrm-color-border);
}
.tabs__close,
.tabs__more,
.tabs__menu button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.tabs__more {
  width: 40px;
  color: var(--tcrm-color-text-secondary);
}
.tabs__menu {
  position: fixed;
  z-index: 40;
  min-width: 168px;
  padding: var(--tcrm-space-1);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-control);
  box-shadow: var(--tcrm-shadow-overlay);
}
.tabs__menu button {
  display: flex;
  width: 100%;
  height: 32px;
  padding: 0 var(--tcrm-space-2);
  border-radius: var(--tcrm-radius-tag);
  color: var(--tcrm-color-text);
  justify-content: flex-start;
}
.tabs__menu button:hover {
  background: var(--tcrm-color-muted);
}
</style>
