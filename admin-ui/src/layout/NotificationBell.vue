<script setup lang="ts">
import { Avatar, Bell, CircleCheck, Clock, Switch, Wallet, Warning } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  unreadNotificationCount,
  type NotificationType,
  type NotificationVO
} from "@/api/notification/notification";
import { useVisibilityPolling } from "@/composables/useVisibilityPolling";
import { useUserStore } from "@/stores/user";
import { notifyError } from "@/utils/notify";
import {
  canOpenNotification,
  notificationHref,
  relativeNotificationTime,
  unreadBadge
} from "@/utils/notification";
import NotificationText from "@/components/NotificationText.vue";
import { accessOf } from "@/utils/permission";

const { t } = useI18n();
const router = useRouter();
const user = useUserStore();
const access = computed(() => accessOf(user.user));

const count = ref(0);
const items = ref<NotificationVO[]>([]);
const open = ref(false);

const badge = computed(() => unreadBadge(count.value));

const icons: Record<NotificationType, typeof Wallet> = {
  PREPAY_SUBMITTED: Wallet,
  PREPAY_APPROVED: Wallet,
  PREPAY_REJECTED: Wallet,
  PREPAY_PAID: Wallet,
  WITHDRAWAL_SUBMITTED: Wallet,
  WITHDRAWAL_APPROVED: Wallet,
  WITHDRAWAL_REJECTED: Wallet,
  WITHDRAWAL_PAID: Wallet,
  PAYOUT_ACCOUNT_SUBMITTED: Wallet,
  ORDER_SYNC_FAILED: Warning,
  BD_TRANSFER_CANCELLED: Switch,
  KOLS_UNASSIGNED: Avatar,
  DEPARTURE_PLAN_SUBMITTED: Clock,
  DEPARTURE_PLAN_EXECUTED: CircleCheck
};

async function refreshCount() {
  count.value = await unreadNotificationCount();
}

async function refreshList() {
  const page = await listNotifications(1, 10, false);
  items.value = page.items;
}

async function refresh() {
  await Promise.all([refreshCount(), open.value ? refreshList() : Promise.resolve()]);
}

useVisibilityPolling(() => refresh().catch(() => undefined));
function refreshAfterPrepay() {
  void refresh().catch(() => undefined);
}
onMounted(() => window.addEventListener("prepay-updated", refreshAfterPrepay));
onBeforeUnmount(() => window.removeEventListener("prepay-updated", refreshAfterPrepay));

async function onOpen() {
  open.value = true;
  try {
    await refreshList();
  } catch (error) {
    notifyError(error);
  }
}

function onHide() {
  open.value = false;
}

async function markAll() {
  try {
    await markAllNotificationsRead();
    count.value = 0;
    items.value = items.value.map(item =>
      item.readTime ? item : { ...item, readTime: new Date().toISOString() }
    );
  } catch (error) {
    notifyError(error);
  }
}

async function openItem(item: NotificationVO) {
  if (!item.readTime) {
    try {
      await markNotificationRead(item.id);
      item.readTime = new Date().toISOString();
      count.value = Math.max(0, count.value - 1);
    } catch (error) {
      notifyError(error);
      return;
    }
  }
  if (!canOpenNotification(item.type, access.value)) {
    ElMessage.warning(t("notification.noPermission"));
    return;
  }
  await router.push(notificationHref(item));
}
</script>

<template>
  <el-popover
    trigger="click"
    placement="bottom-end"
    :width="360"
    popper-class="notification-bell-popover"
    :popper-options="{ modifiers: [{ name: 'preventOverflow', options: { padding: 12 } }] }"
    @after-leave="onHide"
  >
    <template #reference>
      <el-badge
        :value="badge ?? undefined"
        :hidden="badge === null"
        data-test="notification-badge"
        @click="onOpen"
      >
        <el-button text data-test="notification-bell" :aria-label="t('notification.open')">
          <el-icon><Bell /></el-icon>
        </el-button>
      </el-badge>
    </template>
    <div class="notification-bell__panel">
      <div class="notification-bell__list">
        <p v-if="items.length === 0" class="notification-bell__empty">
          {{ t("notification.empty") }}
        </p>
        <button
          v-for="item in items"
          :key="item.id"
          type="button"
          class="notification-bell__item"
          :class="{ 'notification-bell__item--unread': !item.readTime }"
          data-test="notification-item"
          @click="openItem(item)"
        >
          <el-icon><component :is="icons[item.type] ?? Bell" /></el-icon>
          <NotificationText :item="item" class="notification-bell__text" />
          <span class="notification-bell__time">{{
            relativeNotificationTime(item.createTime)
          }}</span>
        </button>
      </div>
      <div class="notification-bell__footer">
        <el-button text data-test="notification-mark-all" @click="markAll">
          {{ t("notification.markAll") }}
        </el-button>
        <el-button
          type="primary"
          text
          data-test="notification-view-all"
          @click="router.push('/notifications')"
        >
          {{ t("notification.viewAll") }}
        </el-button>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.notification-bell__panel {
  display: flex;
  flex-direction: column;
  gap: var(--tcrm-space-2);
  max-height: calc(100dvh - var(--tcrm-header-height) - var(--tcrm-space-5) - var(--tcrm-space-5));
}

:global(.notification-bell-popover) {
  max-width: calc(100vw - var(--tcrm-space-5));
}

.notification-bell__list {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.notification-bell__empty {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.notification-bell__item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: var(--tcrm-space-1) var(--tcrm-space-2);
  padding: var(--tcrm-space-2);
  border: 0;
  background: transparent;
  text-align: start;
  cursor: pointer;
}

.notification-bell__item:hover {
  background: var(--tcrm-color-muted);
}
.notification-bell__footer :deep(.el-button) {
  font-weight: 600;
}
.notification-bell__item--unread {
  font-weight: 600;
}

.notification-bell__text {
  grid-column: 2;
}

.notification-bell__time {
  grid-column: 2;
  font-size: var(--tcrm-font-size-body);
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.notification-bell__footer {
  flex-shrink: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
  justify-content: space-between;
}
@media (max-width: 767px) {
  .notification-bell__item,
  .notification-bell__footer :deep(.el-button) {
    min-height: var(--tcrm-control-height-mobile);
  }
  .notification-bell__footer :deep(.el-button + .el-button) {
    margin-left: 0;
  }
}
</style>
