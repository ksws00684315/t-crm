import { defineStore } from "pinia";
import { computed, nextTick, ref } from "vue";
import {
  clearStoredTabs,
  closeAll,
  closeLeft,
  closeOthers,
  closeRight,
  closeTab,
  loadStoredTabs,
  openTab,
  saveStoredTabs,
  type TabRecord,
  type TabSnapshot
} from "@/utils/tabs";

/**
 * Tabs opened in this browser session. KeepAlive's include list is the component names of the
 * open tabs; refresh drops one name for a tick and bumps that tab's key so only that page remounts.
 */
export const useTabsStore = defineStore("tabs", () => {
  const tabs = ref<TabRecord[]>([]);
  const active = ref("");
  const generations = ref<Record<string, number>>({});
  const suspended = ref<string | null>(null);

  const include = computed(() =>
    tabs.value.map(tab => tab.componentName).filter(name => name !== suspended.value)
  );

  function current(): TabSnapshot {
    return { v: 1, active: active.value, tabs: tabs.value };
  }

  function apply(next: TabSnapshot): void {
    tabs.value = next.tabs;
    active.value = next.active;
    saveStoredTabs(next);
  }

  function restore(affix: TabRecord, canAccess: (path: string) => boolean): void {
    apply(loadStoredTabs(affix, canAccess));
  }

  function open(tab: TabRecord): void {
    apply(openTab(current(), tab));
  }

  function close(path: string): string {
    const next = closeTab(current(), path);
    apply(next);
    return next.active;
  }

  function closeOtherTabs(path: string): string {
    const next = closeOthers(current(), path);
    apply(next);
    return next.active;
  }

  function closeTabsLeft(path: string): string {
    const next = closeLeft(current(), path);
    apply(next);
    return next.active;
  }

  function closeTabsRight(path: string): string {
    const next = closeRight(current(), path);
    apply(next);
    return next.active;
  }

  function closeEveryTab(): string {
    const next = closeAll(current());
    apply(next);
    return next.active;
  }

  /** Remounts one cached page. Other open pages stay mounted. */
  function refresh(path: string): void {
    const tab = tabs.value.find(item => item.path === path);
    generations.value = {
      ...generations.value,
      [path]: (generations.value[path] ?? 0) + 1
    };
    if (!tab) return;
    suspended.value = tab.componentName;
    void nextTick(() => {
      if (suspended.value === tab.componentName) suspended.value = null;
    });
  }

  function keyFor(path: string): string {
    return `${path}:${generations.value[path] ?? 0}`;
  }

  function reset(): void {
    tabs.value = [];
    active.value = "";
    generations.value = {};
    suspended.value = null;
    clearStoredTabs();
  }

  return {
    tabs,
    active,
    include,
    restore,
    open,
    close,
    closeOtherTabs,
    closeTabsLeft,
    closeTabsRight,
    closeEveryTab,
    refresh,
    keyFor,
    reset
  };
});
