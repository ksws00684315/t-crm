import { ElMessage } from "element-plus";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
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
import { accessOf } from "@/utils/permission";
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
  /**
   * True while the page area still belongs to the previous company. The reset does two steps —
   * unmount those pages, then land on the new company's home — and the old pages (list, detail)
   * must not run their own load or open tabs in between.
   */
  const resetting = ref(false);

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
      const nextAffix = affixFor(accessOf(user.user).isPlatformAdmin, company === "KOL_LIBRARY");
      // Only the new company's home tab: close everything, then go to the new company's home and
      // pin it, so the previous company's tabs never come back. (`router.replace` is the router of
      // the running app; the pinned tab is fixed even when the navigation itself is refused.)
      resetting.value = true;
      cacheKey.value = "";
      tabs.closeEveryTab();
      await nextTick();
      await router.replace(nextAffix.path);
      // A reload of the tab list must not bring the previous edition's affix back while the route
      // change below is still in flight.
      // Only pages that stay on their own route survive: everything the previous edition's router
      // sends elsewhere (its home, pages of the other edition) is dropped.
      const canAccess = (path: string) => {
        const resolved = router.resolve(path).path;
        return resolved === path || (router.resolve(path).name === null && resolved === "/404");
      };
      tabs.restore(nextAffix, canAccess, user.user?.id ?? null);
      cacheKey.value = company;
      resetting.value = false;
      // A page that finished loading during the route change may have opened its own tab: the
      // switch keeps only the new home.
      tabs.restore(nextAffix, canAccess, user.user?.id ?? null);
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

  return { companies, switchable, currentId, switching, cacheKey, resetting, switchTo };
}
