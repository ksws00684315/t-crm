import ElementPlus from "element-plus";
import en from "element-plus/es/locale/lang/en";
import "element-plus/dist/index.css";
import { createPinia } from "pinia";
import { createApp } from "vue";
import App from "./App.vue";
import { setupHttp } from "./api/setup";
import router from "./router";
import "./style/index.css";

const app = createApp(App);
app.use(createPinia());
setupHttp(router);
app.use(router);
app.use(ElementPlus, { locale: en });
app.mount("#app");
