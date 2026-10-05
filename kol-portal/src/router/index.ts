import { createRouter, createWebHistory } from "vue-router";
import { useSessionStore } from "@/stores/session";
import { installGuard } from "./guard";
import { routes } from "./routes";

const router = createRouter({ history: createWebHistory(), routes });

installGuard(router, () => ({ isLoggedIn: useSessionStore().isLoggedIn }));

export default router;
