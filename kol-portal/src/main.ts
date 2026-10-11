import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import { createPinia } from "pinia";
import { createApp } from "vue";
import App from "./App.vue";
import { setupHttp } from "./api/setup";
import { i18n } from "./locales";
import router from "./router";
import { useLocaleStore } from "./stores/locale";
import "./style/index.css";

const app = createApp(App);
app.use(createPinia());
// Creates the locale store once, so the persisted language reaches i18n, Element Plus and
// <html lang> before the first render.
useLocaleStore();
setupHttp(router);
app.use(router);
app.use(ElementPlus);
app.use(i18n);
app.mount("#app");
