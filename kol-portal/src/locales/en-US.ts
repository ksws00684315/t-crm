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
    account: "Email",
    password: "Password",
    submit: "Sign in",
    forgot: "Forgot password?",
    selectCompany: "Choose your company",
    chooseCompany: "Please choose a company.",
    continue: "Continue"
  },
  auth: {
    email: "Email",
    emailInvalid: "Please enter a valid email address.",
    passwordRule: "Use 10–72 printable ASCII characters for your password.",
    passwordMismatch: "The passwords do not match.",
    newPassword: "New password",
    confirmPassword: "Confirm password",
    backToLogin: "Back to sign in",
    activateTitle: "Activate your account",
    activateSubmit: "Activate and sign in",
    forgotTitle: "Forgot password",
    sendReset: "Send reset email",
    forgotSent: "If this email is registered, we have sent a password reset email.",
    resetTitle: "Reset password",
    resetSubmit: "Reset password",
    resetSuccess: "Your password has been reset. Please sign in."
  },
  account: {
    title: "My account",
    kolCode: "Creator code",
    name: "Name",
    email: "Email",
    country: "Country",
    tenantName: "Company",
    unavailable: "Not provided",
    sheet: "Promotion sheet",
    openSheet: "Open promotion sheet",
    changePassword: "Change password",
    oldPassword: "Current password",
    passwordChanged: "Your password has been changed.",
    retry: "Retry"
  },
  home: {
    title: "Welcome",
    body: "This portal is under construction. Your sales, commission and withdrawal overview will appear here."
  },
  /** Route `meta.title` values, translated when document.title is set. */
  route: {
    login: "Sign in",
    home: "Home",
    activate: "Activate account",
    reset: "Reset password",
    forgot: "Forgot password",
    account: "My account",
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
    AUTH_INVALID_CREDENTIALS: "Incorrect email or password.",
    AUTH_ACCOUNT_LOCKED: "Too many failed sign-in attempts. Please try again later.",
    AUTH_ACCOUNT_DISABLED: "This account is disabled or unavailable.",
    /** Text for error codes without a key above, for network failures and for rejected fields. */
    KOL_AUTH_TOKEN_INVALID:
      "This link is invalid or expired. Please contact your representative for a new link.",
    AUTH_OLD_PASSWORD_INCORRECT: "Your current password is incorrect.",
    KOL_LOGIN_SELECTION_INVALID: "Company selection has expired. Please sign in again.",
    PAYOUT_ACCOUNT_LIMIT: "You can keep at most 5 active payout accounts.",
    PAYOUT_ACCOUNT_LOCKED: "Approved accounts cannot be changed. Add a new one instead.",
    PAYOUT_ACCOUNT_NOT_APPROVED: "Only approved accounts can be the default.",
    FILE_INVALID: "The file must be a PNG or JPEG image.",
    FILE_TOO_LARGE: "The file is larger than 2 MB.",
    FILE_IN_USE: "This file is already used by another account.",
    generic: "The operation failed. Please try again later.",
    network: "Network error. Please check your connection and try again.",
    field: "This field is not valid."
  }
};

export default enUS;
export type MessageSchema = typeof enUS;
