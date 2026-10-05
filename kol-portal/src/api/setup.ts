import { ElMessage } from "element-plus";
import type { Router } from "vue-router";
import { LOGIN_PATH } from "@/router/guard";
import { useSessionStore } from "@/stores/session";
import { setUnauthorizedHandler } from "./http";

/** HTTP 401 means the session is gone: clear local state and go back to /login. */
export function setupHttp(router: Router): void {
  setUnauthorizedHandler(error => {
    useSessionStore().clearSession();
    const current = router.currentRoute.value;
    if (current.path === LOGIN_PATH) return;
    error.handled = true;
    ElMessage.warning("Your session has expired. Please sign in again.");
    void router.replace({ path: LOGIN_PATH, query: { redirect: current.fullPath } });
  });
}
