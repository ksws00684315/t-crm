import { fileURLToPath, URL } from "node:url";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
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

/** Shared, disposable state for the two local commission preview origins. */
function commissionPreviewStatePlugin(): Plugin {
  const file = fileURLToPath(
    new URL("../.cache/integrated-preview/commission-demo-state.json", import.meta.url)
  );
  let revision = 0;
  let state: unknown = null;
  let writes = Promise.resolve();
  return {
    name: "tcrm-commission-preview-state",
    async configureServer(server) {
      try {
        const saved = JSON.parse(await readFile(file, "utf8")) as {
          revision: number;
          state: unknown;
        };
        if (Number.isSafeInteger(saved.revision) && saved.revision >= 0) {
          revision = saved.revision;
          state = saved.state;
        }
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      }
      server.middlewares.use((request, response, next) => {
        if (request.url !== "/__commission_preview__/state") return next();
        const origin = request.headers.origin;
        const portalOrigin = process.env.VITE_COMMISSION_PREVIEW_PORTAL_ORIGIN;
        if (origin && origin === portalOrigin) {
          response.setHeader("Access-Control-Allow-Origin", origin);
          response.setHeader("Vary", "Origin");
          response.setHeader("Access-Control-Allow-Methods", "GET, PUT, DELETE, OPTIONS");
          response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Type", "application/json; charset=utf-8");
        if (request.method === "OPTIONS") {
          response.statusCode = origin === portalOrigin ? 204 : 403;
          response.end();
          return;
        }
        if (request.method === "GET") {
          response.end(JSON.stringify({ revision, state }));
          return;
        }
        if (request.method !== "PUT" && request.method !== "DELETE") {
          response.statusCode = 405;
          response.end(JSON.stringify({ error: "Method not allowed" }));
          return;
        }
        void (async () => {
          let body: { revision?: number; state?: unknown } = {};
          if (request.method === "PUT") {
            let raw = "";
            for await (const chunk of request) {
              raw += chunk.toString();
              if (raw.length > 2_000_000) throw new Error("Preview state is too large");
            }
            body = JSON.parse(raw) as typeof body;
          }
          const operation = writes.then(async () => {
            if (request.method === "PUT" && body.revision !== revision) {
              response.statusCode = 409;
              response.end(JSON.stringify({ error: "Preview state changed; refresh and retry" }));
              return;
            }
            const nextRevision = revision + 1;
            const nextState = request.method === "DELETE" ? null : body.state;
            const saved = JSON.stringify({ revision: nextRevision, state: nextState });
            await mkdir(dirname(file), { recursive: true });
            await writeFile(`${file}.tmp`, saved, "utf8");
            await rename(`${file}.tmp`, file);
            revision = nextRevision;
            state = nextState;
            response.end(JSON.stringify({ revision }));
          });
          writes = operation.catch(() => undefined);
          await operation;
        })().catch(() => {
          response.statusCode = 500;
          response.end(JSON.stringify({ error: "Preview state unavailable" }));
        });
      });
    }
  };
}

// Development proxy: the admin console talks to the backend under /admin-api.
const backend = process.env.VITE_ADMIN_API_TARGET ?? "http://localhost:8080";

export default defineConfig(({ mode }) => ({
  plugins: [
    vue(),
    appVersionPlugin(),
    ...(mode === "commission-preview" && process.env.VITE_COMMISSION_DEMO === "true"
      ? [commissionPreviewStatePlugin()]
      : [])
  ],
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
}));
