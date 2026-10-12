import { ElMessage } from "element-plus";
import type { Router } from "vue-router";
import { messageForErrorCode } from "@/api/error-codes";
import { useReauthStore } from "@/stores/reauth";
import { useTabsStore } from "@/stores/tabs";
import { useUserStore } from "@/stores/user";
import { LOGIN_PATH } from "@/router/guard";
import { decideUnauthorized } from "@/utils/reauth";
import { setUnauthorizedHandler } from "./http";

/**
 * A 401 for the signed-in user opens the reauth dialog and keeps the page.
 * Disabled accounts and signed-out callers still return to the login page.
 * Account lockout is HTTP 429 and is handled inside ReauthDialog, not here.
 */
export function setupHttp(router: Router): void {
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
