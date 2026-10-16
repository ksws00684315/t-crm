import { ElMessage } from "element-plus";
import type { Router } from "vue-router";
import { messageForErrorCode } from "@/api/error-codes";
import { useReauthStore } from "@/stores/reauth";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import { LOGIN_PATH } from "@/router/guard";
import { translate } from "@/locales";
import { shownCompanyId, singleFlight } from "@/utils/company";
import { decideUnauthorized } from "@/utils/reauth";
import { setCompanyIdSource, setCompanySwitchedHandler, setUnauthorizedHandler } from "./http";

/**
 * A 401 for the signed-in user opens the reauth dialog and keeps the page.
 * Disabled accounts and signed-out callers still return to the login page.
 * Account lockout is HTTP 429 and is handled inside ReauthDialog, not here.
 */
export function setupHttp(router: Router): void {
  // From the in-memory store, not localStorage: the stored session already holds the company a
  // sibling tab switched to, while this tab may still be showing the previous one.
  setCompanyIdSource(() => {
    const user = useUserStore();
    return user.isLoggedIn ? shownCompanyId(user.user) : null;
  });
  // The layout reacts to the new company (tabs, cache, home); see useCompanySync.
  const reloadCompany = singleFlight(async () => {
    ElMessage.warning(translate("company.switchedElsewhere"));
    await useUserStore()
      .refreshProfile()
      .catch(() => undefined);
  });
  setCompanySwitchedHandler(error => {
    error.handled = true;
    void reloadCompany();
  });
  setUnauthorizedHandler(error => {
    const user = useUserStore();
    const reauth = useReauthStore();
    const tabs = useTabsStore();
    const current = router.currentRoute.value;
    const action = decideUnauthorized({
      onLoginPage: current.path === LOGIN_PATH,
      loggedIn: user.isLoggedIn,
      errorCode: error.errorCode,
      dialogOpen: reauth.visible
    });
    if (action === "ignore") {
      if (current.path !== LOGIN_PATH) error.handled = true;
      return;
    }
    if (action === "reauth") {
      error.handled = true;
      reauth.open();
      return;
    }
    // login: close the dialog first so a later layout mount does not reopen it
    reauth.close();
    tabs.reset();
    user.clearSession();
    if (current.path === LOGIN_PATH) return;
    error.handled = true;
    ElMessage.warning(messageForErrorCode(error.errorCode ?? "AUTH_UNAUTHORIZED"));
    void router.replace({ path: LOGIN_PATH, query: { redirect: current.fullPath } });
  });
}
