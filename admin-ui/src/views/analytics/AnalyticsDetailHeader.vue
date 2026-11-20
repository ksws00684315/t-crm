<script setup lang="ts">
import { computed, ref } from "vue";
import { Goods, User } from "@element-plus/icons-vue";
import { useI18n } from "vue-i18n";
import CopyText from "@/components/CopyText.vue";
import type { Board, Filters, RankRow } from "@/api/analytics";
import CreatorIdentity from "./CreatorIdentity.vue";

const props = defineProps<{
  board: Board;
  name: string;
  row: RankRow;
  period: Pick<Filters, "startDate" | "endDate">;
}>();
const { t, locale } = useI18n();
const product = computed(() => props.board === "best-sellers");
const creator = computed(() => props.board === "kol-ranking");
const inviter = computed(() => props.board === "inviter-performance");
const failedImage = ref<string>();
const initial = computed(() => Array.from(props.name.trim())[0]?.toLocaleUpperCase(locale.value));
const avatar = computed(() => props.row.imageUrl !== failedImage.value && props.row.imageUrl);
const invited = computed(() => {
  const value = props.row.invitedCount;
  return typeof value === "string" || typeof value === "number"
    ? new Intl.NumberFormat(locale.value).format(BigInt(value))
    : "—";
});
const commission = computed(() => {
  const value = props.row.commissionRate;
  return value != null && Number.isFinite(Number(value))
    ? new Intl.NumberFormat(locale.value, {
        style: "percent",
        maximumFractionDigits: 2
      }).format(Number(value))
    : "";
});
</script>

<template>
  <section class="detail-header analytics-detail__identity">
    <div class="detail-header__identity">
      <div
        class="detail-header__avatar"
        :class="{
          'detail-header__avatar--product': product,
          'detail-header__avatar--inviter': inviter
        }"
        aria-hidden="true"
      >
        <img v-if="avatar" :src="avatar" alt="" @error="failedImage = avatar" />
        <el-icon v-else-if="product"><Goods /></el-icon>
        <span v-else-if="initial">{{ initial }}</span>
        <el-icon v-else><User /></el-icon>
      </div>
      <div class="detail-header__text">
        <div class="detail-header__title">
          <CreatorIdentity v-if="creator" :name="name" :code="row.code" heading />
          <h2 v-else>{{ name }}</h2>
          <el-tag
            v-if="!product && row.enabled != null"
            :type="row.enabled ? 'success' : 'info'"
            size="small"
          >
            {{ t(row.enabled ? "analytics.enabled" : "analytics.disabled") }}
          </el-tag>
        </div>
        <div v-if="product" class="detail-header__metadata">
          <span>{{ t("analytics.shopDrawer.spuId") }}</span>
          <CopyText v-if="row.sourceItemId" :value="row.sourceItemId" />
          <span v-else>—</span>
        </div>
        <CopyText
          v-else-if="inviter && row.code"
          :value="row.code"
          class="detail-header__metadata"
        />
        <div v-if="creator && (row.related || commission)" class="detail-header__metadata">
          <span v-if="row.related">{{ t("analytics.inviter") }} {{ row.related }}</span>
          <span v-if="commission"
            >{{ t("analytics.kol-ranking.commissionRate") }} {{ commission }}</span
          >
        </div>
      </div>
    </div>
    <div class="detail-header__aside">
      <div v-if="inviter" class="detail-header__count">
        <el-tooltip
          :content="t('analytics.detailHeader.totalInvitedHint')"
          :trigger="['hover', 'focus']"
        >
          <span tabindex="0">{{ t("analytics.detailHeader.totalInvited") }}</span>
        </el-tooltip>
        <strong>{{ invited }}</strong>
      </div>
      <time :datetime="period.endDate">
        {{ period.startDate }} – {{ period.endDate }} · {{ t("analytics.drilldown.beijingTime") }}
      </time>
    </div>
  </section>
</template>

<style scoped>
.detail-header.analytics-detail__identity {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--tcrm-space-4);
  padding: var(--tcrm-space-4);
  border: 1px solid var(--tcrm-color-border);
  border-radius: var(--tcrm-radius-card);
  background: var(--tcrm-color-card);
}
.detail-header__identity,
.detail-header__title,
.detail-header__metadata {
  display: flex;
  align-items: center;
  min-width: 0;
}
.detail-header__identity {
  gap: var(--tcrm-space-3);
}
.detail-header__avatar {
  display: grid;
  flex: 0 0 52px;
  width: 52px;
  height: 52px;
  place-items: center;
  overflow: hidden;
  border-radius: var(--tcrm-radius-control);
  background: var(--tcrm-color-option-selected-bg);
  color: var(--tcrm-color-primary-text);
  font-size: var(--tcrm-font-size-title);
  font-weight: 600;
}
.detail-header__avatar--product {
  border: 1px solid var(--tcrm-color-border);
  background: var(--tcrm-color-muted);
}
.detail-header__avatar--inviter {
  background: var(--tcrm-color-success-bg);
  color: var(--tcrm-color-success);
}
.detail-header__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.detail-header__text,
.detail-header__aside,
.detail-header__count {
  display: grid;
  min-width: 0;
  gap: var(--tcrm-space-1);
}
.detail-header__title,
.detail-header__metadata {
  flex-wrap: wrap;
  gap: var(--tcrm-space-2);
}
.detail-header__title h2,
.detail-header__title :deep(h2) {
  margin: 0;
  font-size: var(--tcrm-font-size-section);
  font-weight: 600;
  overflow-wrap: anywhere;
}
.detail-header__metadata,
.detail-header__aside {
  color: var(--tcrm-color-text-secondary);
  font-size: var(--tcrm-font-size-help);
}
.detail-header__aside {
  justify-items: end;
  gap: var(--tcrm-space-2);
}
.detail-header__count {
  justify-items: end;
  padding-block: var(--tcrm-space-1);
  padding-inline-start: var(--tcrm-space-3);
  border-inline-start: 2px solid var(--tcrm-color-success);
}
.detail-header__count strong {
  color: var(--tcrm-color-text);
  font-variant-numeric: tabular-nums;
  font-size: var(--tcrm-font-size-dashboard-metric);
  line-height: var(--tcrm-line-height-dashboard-metric);
}
@media (max-width: 720px) {
  .detail-header.analytics-detail__identity {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--tcrm-space-3);
  }
  .detail-header__aside {
    justify-items: start;
    padding-block-start: var(--tcrm-space-2);
    border-block-start: 1px solid var(--tcrm-color-border);
  }
  .detail-header__count {
    justify-items: start;
  }
}
</style>
