import { ElMessage } from "element-plus";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import type { CompanyVO } from "@/api/types";
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
import { accessOf, canAccessRoute } from "@/utils/permission";
import { affixFor } from "@/utils/tabs";

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
      // Close every tab of the previous company, then land on the new company's own home: the
      // workbench for a distribution company, the creator list for a creator library (ADR-038 §3.3).
      // The saved tab list is rewritten for the new edition so a reload keeps that home pinned.
      tabs.closeEveryTab();
      const nextAffix = affixFor(accessOf(user.user).isPlatformAdmin, company === "KOL_LIBRARY");
      tabs.restore(
        nextAffix,
        path => canAccessRoute(router.resolve(path).meta, accessOf(user.user)),
        user.user?.id ?? null
      );
      await router.replace(nextAffix.path);
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
