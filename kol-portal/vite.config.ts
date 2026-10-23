import { fileURLToPath, URL } from "node:url";
import vue from "@vitejs/plugin-vue";
import { loadEnv } from "vite";
import { defineConfig } from "vitest/config";

// Development proxy: the portal talks to the backend under /kol-api.
export default defineConfig(({ mode }) => {
  const localEnv = loadEnv(mode, fileURLToPath(new URL(".", import.meta.url)), "VITE_");
  const backend =
    process.env.VITE_KOL_API_TARGET ?? localEnv.VITE_KOL_API_TARGET ?? "http://localhost:8080";
  return {
    plugins: [vue()],
    resolve: {
      alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) }
    },
    server: {
      port: 8849,
      proxy: {
        "/kol-api": { target: backend, changeOrigin: true }
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
  };
});
