import { ElMessage } from "element-plus";
import type { Router } from "vue-router";
import { messageForErrorCode } from "@/api/error-codes";
import { useUserStore } from "@/stores/user";
import { LOGIN_PATH } from "@/router/guard";
import { setUnauthorizedHandler } from "./http";

/**
 * HTTP 401 means the session is gone (expired, logged out elsewhere, password changed, account
 * disabled). Clear local state and go back to the login page, remembering the current page.
 */
export function setupHttp(router: Router): void {
  setUnauthorizedHandler(error => {
    useUserStore().clearSession();
    const current = router.currentRoute.value;
    if (current.path === LOGIN_PATH) return; // a failed login attempt: the page shows its own error
    error.handled = true;
    ElMessage.warning(messageForErrorCode("AUTH_UNAUTHORIZED"));
    void router.replace({ path: LOGIN_PATH, query: { redirect: current.fullPath } });
  });
}
