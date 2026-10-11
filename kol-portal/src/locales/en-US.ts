/**
 * Base message pack. Keys follow "area.meaning" (common.signOut, login.title, errors.AUTH_*,
 * route.notFound); never the English or Chinese sentence itself. `zh-CN.ts` must have exactly the
 * same keys: its type is `MessageSchema` below, so a missing or extra key fails `pnpm typecheck`,
 * and messages.spec.ts checks the same at runtime.
 */
const enUS = {
  app: {
    title: "T-CRM Creator Portal"
  },
  common: {
    language: "Language",
    signOut: "Sign out",
    backToHome: "Back to home",
    comingSoon: "Coming soon"
  },
  /** Each language is named in itself and shows the same label in both languages. */
  locale: {
    "en-US": "English",
    "zh-CN": "中文"
  },
  login: {
    title: "T-CRM Creator Portal",
    account: "Account",
    password: "Password",
    submit: "Sign in",
    hint: "Creator sign-in will be available in a later release."
  },
  home: {
    title: "Welcome",
    body: "This portal is under construction. Your sales, commission and withdrawal overview will appear here."
  },
  /** Route `meta.title` values, translated when document.title is set. */
  route: {
    login: "Sign in",
    home: "Home",
    notFound: "Page not found"
  },
  errors: {
    COMMON_VALIDATION_FAILED:
      "Some of the information you entered is not valid. Please check and try again.",
    COMMON_NOT_FOUND: "The requested item does not exist.",
    COMMON_METHOD_NOT_ALLOWED: "This action is not supported.",
    COMMON_CONFLICT: "The data has changed. Please refresh and try again.",
    COMMON_INTERNAL_ERROR: "Something went wrong on our side. Please try again later.",
    AUTH_UNAUTHORIZED: "Your session has expired. Please sign in again.",
    AUTH_FORBIDDEN: "You do not have permission to do this.",
    AUTH_INVALID_CREDENTIALS: "Incorrect account or password.",
    AUTH_ACCOUNT_LOCKED: "Too many failed sign-in attempts. Please try again later.",
    AUTH_ACCOUNT_DISABLED: "This account is disabled or unavailable.",
    /** Text for error codes without a key above, for network failures and for rejected fields. */
    generic: "The operation failed. Please try again later.",
    network: "Network error. Please check your connection and try again.",
    field: "This field is not valid."
  }
};

export default enUS;
export type MessageSchema = typeof enUS;
