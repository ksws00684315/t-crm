import { ElMessage } from "element-plus";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import type { CompanyVO } from "@/api/types";
import { usePayoutPendingStore } from "@/stores/payout-pending";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import {
  canSwitchCompany,
  companiesOf,
  COMPANY_CHANNEL,
  shownCompanyId,
  type CompanySwitchedMessage
} from "@/utils/company";
import { ApiError } from "@/utils/http/error";
import { notifyError } from "@/utils/notify";
import { homeAffix } from "@/utils/tabs";

/**
 * Company switching in the header (task 21b, ADR-022). The session's current company lives on the
 * server; this tab only shows it. Whatever moves the shown company (a switch here, a message from
 * another tab, a 409 refresh, a re-login) ends in the same reset: only the home tab stays, the
 * KeepAlive cache is dropped through `cacheKey`, and the tab returns home.
 */
export function useCompanySync() {
  const { t } = useI18n();
  const router = useRouter();
  const user = useUserStore();
  const tabs = useTabsStore();
  const payoutPending = usePayoutPendingStore();

  const companies = computed<CompanyVO[]>(() => companiesOf(user.user));
  const switchable = computed(() => canSwitchCompany(user.user));
  const currentId = computed(() => shownCompanyId(user.user));
  const switching = ref(false);
  /** Key of the page area; a new value unmounts every cached page. */
  const cacheKey = ref(currentId.value ?? "");

  let channel: BroadcastChannel | null = null;

  async function switchTo(tenantId: string): Promise<void> {
    if (switching.value || tenantId === currentId.value) return;
    switching.value = true;
    try {
      await user.switchCompany(tenantId);
      const message: CompanySwitchedMessage = { type: "switched", tenantId };
      channel?.postMessage(message);
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === "AUTH_COMPANY_NOT_MEMBER") {
        ElMessage.error(error.displayMessage);
        await user.refreshProfile().catch(() => undefined);
      } else {
        notifyError(error);
      }
    } finally {
      switching.value = false;
    }
  }

  watch(
    () => [user.user?.id ?? null, currentId.value] as const,
    async ([id, company], [previousId, previousCompany]) => {
      // Sign-in, sign-out and a different account are handled by the login and lock flows.
      if (!id || id !== previousId || !company || !previousCompany || company === previousCompany) {
        return;
      }
      tabs.closeEveryTab();
      // The pending-review badge belongs to the previous company; drop it, then refetch with the
      // new company's codes (the fetch is skipped without payout-account:review).
      payoutPending.count = 0;
      void payoutPending.refresh();
      await router.replace(homeAffix().path);
      cacheKey.value = company;
      ElMessage.success(t("company.switched", { name: user.user?.tenantName ?? "" }));
    }
  );

  onMounted(() => {
    if (typeof BroadcastChannel === "undefined") return; // X-Company-Id + 409 still protect
    channel = new BroadcastChannel(COMPANY_CHANNEL);
    channel.onmessage = () => {
      void user.refreshProfile().catch(() => undefined);
    };
  });

  onBeforeUnmount(() => {
    channel?.close();
    channel = null;
  });

  return { companies, switchable, currentId, switching, cacheKey, switchTo };
}
