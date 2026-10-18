/// <reference types="vite/client" />

/** Build id inlined by the Vite plugin in vite.config.ts. Same string as version.json. */
declare const __APP_VERSION__: string;

interface ImportMetaEnv {
  /** Optional base of the promo-sheet upload sub-domain, e.g. https://upload.example.com. */
  readonly VITE_UPLOAD_BASE_URL?: string;
}
