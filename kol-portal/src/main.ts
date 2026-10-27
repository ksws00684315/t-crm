import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import { createPinia } from "pinia";
import { createApp } from "vue";
import App from "./App.vue";
import { loginDemo } from "./api/auth";
import { setupHttp } from "./api/setup";
import { i18n } from "./locales";
import router from "./router";
import { useLocaleStore } from "./stores/locale";
import { useSessionStore } from "./stores/session";
import { loadSiteConfig, startSiteConfigRefresh } from "./stores/site-config";
import { isDemoMode } from "./utils/demo-mode";
import "./style/tokens.css";
import "./style/index.css";

const app = createApp(App);
app.use(createPinia());
// Creates the locale store once, so the persisted language reaches i18n, Element Plus and
// <html lang> before the first render.
useLocaleStore();
if (isDemoMode && new URLSearchParams(window.location.search).get("preview") === "1")
  useSessionStore().acceptSession(loginDemo());
setupHttp(router);
void loadSiteConfig();
startSiteConfigRefresh();
app.use(router);
app.use(ElementPlus);
app.use(i18n);
app.mount("#app");
