import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import { router } from "./router";
import { initApi } from "./api";
import "./styles.css";

/**
 * 启动顺序：先按全局开关决定 mock 或真实请求，再挂载应用。
 * 这样 `USE_MOCK` 只有一个生效点，业务代码对开关无感。
 */
async function bootstrap() {
  await initApi();
  createApp(App).use(createPinia()).use(router).mount("#app");
}

void bootstrap();
