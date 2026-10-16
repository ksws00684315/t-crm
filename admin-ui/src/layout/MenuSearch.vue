<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import { routes } from "@/router/routes";
import { useLocaleStore } from "@/stores/locale";
import { useLockStore } from "@/stores/lock";
import { useMenuSearchStore } from "@/stores/menu-search";
import { useUserStore } from "@/stores/user";
import {
  flattenMenu,
  loadRecentMenus,
  matchMenus,
  rememberRecentMenu,
  type MenuEntry
} from "@/utils/menu-search";
import { buildMenu } from "@/utils/permission";

defineOptions({ name: "MenuSearchDialog" });

const { t } = useI18n();
const router = useRouter();
const user = useUserStore();
const localeStore = useLocaleStore();
const menuSearch = useMenuSearchStore();
const lock = useLockStore();

const query = ref("");
const active = ref(0);
const recentPaths = ref<string[]>([]);
const inputRef = ref<{ focus: () => void } | null>(null);
let previousFocus: HTMLElement | null = null;

const entries = computed(() => {
  void localeStore.locale;
  return flattenMenu(buildMenu(routes, user.access));
});

const results = computed(() => {
  if (!query.value.trim()) {
    const byPath = new Map(entries.value.map(entry => [entry.path, entry]));
    return recentPaths.value
      .map(path => byPath.get(path))
      .filter((entry): entry is MenuEntry => Boolean(entry));
  }
  return matchMenus(entries.value, query.value);
});

watch(results, () => {
  active.value = 0;
});

function onOpen(): void {
  previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  query.value = "";
  active.value = 0;
  recentPaths.value = loadRecentMenus(user.user?.id ?? "");
  void nextTick(() => inputRef.value?.focus());
}

function onClosed(): void {
  previousFocus?.focus();
  previousFocus = null;
}

function move(delta: number): void {
  const count = results.value.length;
  if (count === 0) return;
  active.value = Math.min(count - 1, Math.max(0, active.value + delta));
}

async function choose(index = active.value): Promise<void> {
  const entry = results.value[index];
  const userId = user.user?.id;
  if (!entry || !userId) return;
  recentPaths.value = rememberRecentMenu(userId, entry.path);
  menuSearch.close();
  if (router.currentRoute.value.path !== entry.path) await router.push(entry.path);
}

function onKeydown(event: KeyboardEvent): void {
  const key = event.key.toLowerCase();
  if ((event.ctrlKey || event.metaKey) && key === "k" && !event.altKey) {
    event.preventDefault();
    // Shortcuts must not pierce the lock overlay (layout root is inert; this listener is on window).
    if (lock.locked) return;
    menuSearch.open();
    return;
  }
  if (!menuSearch.visible) return;
  if (event.key === "ArrowDown") {
    event.preventDefault();
    move(1);
  } else if (event.key === "ArrowUp") {
    event.preventDefault();
    move(-1);
  } else if (event.key === "Enter") {
    event.preventDefault();
    void choose();
  } else if (event.key === "Escape") {
    event.preventDefault();
    menuSearch.close();
  }
}

onMounted(() => window.addEventListener("keydown", onKeydown, true));
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown, true));
</script>

<template>
  <el-dialog
    :model-value="menuSearch.visible"
    :title="t('menuSearch.title')"
    width="520px"
    append-to-body
    data-test="menu-search-dialog"
    @open="onOpen"
    @update:model-value="menuSearch.visible = $event"
    @closed="onClosed"
  >
    <el-input
      ref="inputRef"
      v-model="query"
      clearable
      data-test="menu-search-input"
      :placeholder="t('menuSearch.placeholder')"
    />
    <p v-if="!query.trim()" class="menu-search__hint">{{ t("menuSearch.recent") }}</p>
    <p v-else-if="results.length === 0" class="menu-search__hint">{{ t("menuSearch.empty") }}</p>
    <div class="menu-search__list" role="listbox">
      <button
        v-for="(entry, index) in results"
        :key="entry.path"
        type="button"
        role="option"
        data-test="menu-search-item"
        class="menu-search__item"
        :class="{ 'is-active': index === active }"
        :aria-selected="index === active"
        @mouseenter="active = index"
        @click="choose(index)"
      >
        <span>{{ entry.title }}</span>
        <span v-if="entry.group" class="menu-search__group">{{ entry.group }}</span>
      </button>
    </div>
  </el-dialog>
</template>

<style scoped>
.menu-search__hint {
  margin: var(--tcrm-space-3) 0 var(--tcrm-space-2);
  color: var(--tcrm-color-text-secondary);
}
.menu-search__list {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-1);
  max-height: 320px;
  overflow: auto;
}
.menu-search__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
  height: var(--tcrm-control-height);
  padding: 0 var(--tcrm-space-3);
  border: 1px solid transparent;
  border-radius: var(--tcrm-radius-control);
  background: transparent;
  color: var(--tcrm-color-text);
  text-align: left;
  cursor: pointer;
}
.menu-search__item.is-active {
  background: var(--tcrm-color-muted);
}
.menu-search__group {
  color: var(--tcrm-color-text-secondary);
}
</style>
