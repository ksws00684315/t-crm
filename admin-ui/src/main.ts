import * as ElementPlusIcons from "@element-plus/icons-vue";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import "element-plus/dist/index.css";
import { createPinia } from "pinia";
import { createApp } from "vue";
import App from "./App.vue";
import { setupHttp } from "./api/setup";
import router from "./router";
import "./style/index.css";

const app = createApp(App);
for (const [name, icon] of Object.entries(ElementPlusIcons)) {
  app.component(name, icon);
}
app.use(createPinia());
setupHttp(router);
app.use(router);
app.use(ElementPlus, { locale: zhCn });
app.mount("#app");
