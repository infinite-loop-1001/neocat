import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 真实后端联调：前端发相对 `/api`，由 Vite 转发到本机 Spring Boot。
    // 保持同源（浏览器只看到 5173），登录会话 Cookie 才能随请求带回。
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: true
      }
    }
  }
});
