<script setup lang="ts">
import { Avatar, CircleCheck, Clock, Switch, Wallet, Warning } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  type NotificationType,
  type NotificationVO
} from "@/api/notification/notification";
import { useUserStore } from "@/stores/user";
import { notifyError } from "@/utils/notify";
import {
  canOpenNotification,
  notificationHref,
  notificationText,
  relativeNotificationTime
} from "@/utils/notification";
import { accessOf } from "@/utils/permission";

defineOptions({ name: "NotificationPage" });

const { t } = useI18n();
const router = useRouter();
const user = useUserStore();
const access = computed(() => accessOf(user.user));

const unreadOnly = ref(true);
const loading = ref(false);
const rows = ref<NotificationVO[]>([]);
const total = ref(0);
const page = ref(1);
const size = 20;

const icons: Record<NotificationType, typeof Wallet> = {
  PREPAY_SUBMITTED: Wallet,
  PREPAY_APPROVED: Wallet,
  PREPAY_REJECTED: Wallet,
  PREPAY_PAID: Wallet,
  PAYOUT_ACCOUNT_SUBMITTED: Wallet,
  ORDER_SYNC_FAILED: Warning,
  BD_TRANSFER_CANCELLED: Switch,
  KOLS_UNASSIGNED: Avatar,
  DEPARTURE_PLAN_SUBMITTED: Clock,
  DEPARTURE_PLAN_EXECUTED: CircleCheck
};

async function load() {
  loading.value = true;
  try {
    const result = await listNotifications(page.value, size, unreadOnly.value);
    rows.value = result.items;
    total.value = Number(result.total);
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function showUnread() {
  unreadOnly.value = true;
  page.value = 1;
  void load();
}

function showAll() {
  unreadOnly.value = false;
  page.value = 1;
  void load();
}

async function markAll() {
  try {
    await markAllNotificationsRead();
    await load();
  } catch (error) {
    notifyError(error);
  }
}

async function openItem(item: NotificationVO) {
  if (!item.readTime) {
    try {
      await markNotificationRead(item.id);
      item.readTime = new Date().toISOString();
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

function onPage(next: number) {
  page.value = next;
  void load();
}

onMounted(load);
</script>

<template>
  <section class="notification-page" data-test="notification-page">
    <div class="notification-page__toolbar">
      <el-button-group>
        <el-button
          data-test="notification-filter-unread"
          :type="unreadOnly ? 'primary' : 'default'"
          @click="showUnread"
        >
          {{ t("notification.unread") }}
        </el-button>
        <el-button
          data-test="notification-filter-all"
          :type="unreadOnly ? 'default' : 'primary'"
          @click="showAll"
        >
          {{ t("common.all") }}
        </el-button>
      </el-button-group>
      <el-button data-test="notification-mark-all" @click="markAll">
        {{ t("notification.markAll") }}
      </el-button>
    </div>
    <div class="notification-page__card">
      <p v-if="!loading && rows.length === 0" class="notification-page__empty">
        {{ t("notification.empty") }}
      </p>
      <button
        v-for="item in rows"
        :key="item.id"
        type="button"
        class="notification-page__item"
        :class="{ 'notification-page__item--unread': !item.readTime }"
        data-test="notification-item"
        @click="openItem(item)"
      >
        <el-icon><component :is="icons[item.type]" /></el-icon>
        <span>{{ notificationText(item) }}</span>
        <span class="notification-page__time">{{ relativeNotificationTime(item.createTime) }}</span>
      </button>
      <el-pagination
        v-if="total > size"
        class="notification-page__pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPage"
      />
    </div>
  </section>
</template>

<style scoped>
.notification-page {
  display: flex;
  flex-direction: column;
  flex: 1;
  gap: var(--tcrm-space-4);
  height: 100%;
  min-width: 0;
  min-height: 0;
}

.notification-page__toolbar {
  display: flex;
  justify-content: space-between;
  gap: var(--tcrm-space-3);
}

/* Same surface as the list and filter cards: the message list was the only page that rendered
   straight onto the page background, so its empty state floated without a frame. */
.notification-page__card {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: var(--tcrm-space-4);
  background: var(--tcrm-color-card);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
}

/* Centred in whatever space the card has left, the way the table's empty state reads. */
.notification-page__empty {
  margin: auto;
  padding: var(--tcrm-space-6) 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}

.notification-page__item {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: var(--tcrm-space-3);
  align-items: center;
  padding: var(--tcrm-space-3);
  border: 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: start;
  cursor: pointer;
}

.notification-page__item:last-of-type {
  border-bottom: 0;
}

.notification-page__item:hover {
  background: var(--el-fill-color-light);
}

.notification-page__item:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}

.notification-page__item--unread {
  font-weight: 600;
}

.notification-page__time {
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.notification-page__pager {
  align-self: flex-end;
  padding-top: var(--tcrm-space-3);
}
</style>
