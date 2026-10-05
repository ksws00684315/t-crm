import { fileURLToPath, URL } from "node:url";
import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";

// Development proxy: the admin console talks to the backend under /admin-api.
const backend = process.env.VITE_ADMIN_API_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) }
  },
  server: {
    port: 8848,
    proxy: {
      "/admin-api": { target: backend, changeOrigin: true }
    }
  },
  build: {
    target: "es2020",
    sourcemap: false,
    chunkSizeWarningLimit: 1500
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.spec.ts"],
    restoreMocks: true,
    // Load Element Plus through the same module graph as Vue so form injection works in tests.
    server: { deps: { inline: ["element-plus"] } }
  }
});
