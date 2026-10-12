import { fileURLToPath, URL } from "node:url";
import vue from "@vitejs/plugin-vue";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

/** One id for both the compiled constant and version.json. Computed when the config loads. */
function appVersionPlugin(): Plugin {
  const version = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  return {
    name: "tcrm-app-version",
    config() {
      return { define: { __APP_VERSION__: JSON.stringify(version) } };
    },
    generateBundle() {
      this.emitFile({
        type: "asset",
        fileName: "version.json",
        source: `${JSON.stringify({ version })}\n`
      });
    }
  };
}

// Development proxy: the admin console talks to the backend under /admin-api.
const backend = process.env.VITE_ADMIN_API_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [vue(), appVersionPlugin()],
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
    setupFiles: ["src/test/locale-setup.ts"],
    restoreMocks: true,
    // Load Element Plus through the same module graph as Vue so form injection works in tests.
    server: { deps: { inline: ["element-plus"] } }
  }
});
