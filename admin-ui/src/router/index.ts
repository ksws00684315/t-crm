import { createRouter, createWebHistory } from "vue-router";
import { useUserStore } from "@/stores/user";
import { installGuard } from "./guard";
import { routes } from "./routes";

const router = createRouter({ history: createWebHistory(), routes });

installGuard(router, () => {
  const user = useUserStore();
  return {
    isLoggedIn: user.isLoggedIn,
    roles: user.roles,
    dataScope: user.user?.dataScope
  };
});

export default router;
