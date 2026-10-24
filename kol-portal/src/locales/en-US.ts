/**
 * Base message pack. Keys follow "area.meaning" (common.signOut, login.title, errors.AUTH_*,
 * route.notFound); never the English or Chinese sentence itself. `zh-CN.ts` must have exactly the
 * same keys: its type is `MessageSchema` below, so a missing or extra key fails `pnpm typecheck`,
 * and messages.spec.ts checks the same at runtime.
 */
import prepay from "./prepay-en-US";
const enUS = {
  prepay,
  app: {
    title: "SparkTrack Creator Portal",
    titleSuffix: " Creator"
  },
  common: {
    language: "Language",
    signOut: "Sign out",
    backToHome: "Back to home",
    comingSoon: "Coming soon",
    cancel: "Cancel",
    edit: "Edit",
    copy: "Copy",
    actions: "Actions",
    search: "Search",
    reset: "Reset"
  },
  /** Header navigation (task 15b §4.3); keys are referenced by `layout/nav.ts`. */
  nav: {
    label: "Primary",
    home: "Home",
    payoutAccounts: "Payout accounts",
    orders: "My orders",
    items: "My items"
  },
  /** Each language is named in itself and shows the same label in both languages. */
  locale: {
    "en-US": "English",
    "zh-CN": "中文"
  },
  login: {
    title: "SparkTrack Creator Portal",
    account: "Email",
    password: "Password",
    submit: "Sign in",
    forgot: "Forgot password?",
    signUp: "Sign up",
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
    phone: "Phone number",
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
    body: "This portal is under construction. Your sales, commission and withdrawal overview will appear here.",
    viewOrders: "View my orders",
    payoutEntry: "Payout accounts"
  },
  items: {
    title: "My items",
    searchPlaceholder: "Search by item name",
    search: "Search",
    loadFailed: "Could not load your items. Please try again.",
    empty: "No items yet",
    emptyHint: "Your items appear here once your manager finishes the promotion setup.",
    unnamed: "Unnamed item",
    copyLink: "Copy link",
    copied: "Copied",
    colItem: "Item",
    colId: "Item ID",
    colCreated: "Created"
  },
  orders: {
    title: "My orders",
    dateRange: "Date range",
    last7Days: "Last 7 days",
    last30Days: "Last 30 days",
    last90Days: "Last 90 days",
    customRange: "Custom range",
    fromDate: "From date",
    toDate: "To date",
    allStatuses: "All statuses",
    status: "Status",
    orderNo: "Order number",
    payTime: "Payment time",
    payAmount: "Paid",
    refundAmount: "Refund",
    item: "Item",
    sku: "SKU",
    quantity: "Quantity",
    empty: "No orders yet. Share your exclusive link and orders will appear here.",
    loadFailed: "Orders could not be loaded. Please try again.",
    statuses: {
      UNPAID: "Unpaid",
      PAID: "Paid",
      SHIPPED: "Shipped",
      RECEIVED: "Received",
      COMPLETED: "Completed",
      CLOSED: "Closed",
      UNKNOWN: "Unknown"
    }
  },
  /** Route `meta.title` values, translated when document.title is set. */
  route: {
    login: "Sign in",
    home: "Home",
    orders: "My orders",
    items: "My items",
    activate: "Activate account",
    reset: "Reset password",
    forgot: "Forgot password",
    account: "My account",
    payoutAccounts: "Payout accounts",
    register: "Create account",
    notFound: "Page not found"
  },
  payoutAccounts: {
    title: "Payout accounts",
    create: "Add payout account",
    createTitle: "Add payout account",
    editTitle: "Edit payout account",
    empty: "Add a PayPal or USDT account; once approved it is used to pay you.",
    limitReached: "The configured payout account limit has been reached.",
    submitted: "Submitted. Your representative will review it.",
    account: "Account / address",
    holder: "Receiver name",
    submittedAt: "Submitted",
    defaultTag: "Default",
    setDefault: "Set as default",
    defaultSet: "This account is now the default.",
    disable: "Disable",
    disableConfirm: "A disabled account cannot be restored and will no longer be shown.",
    disabled: "The account has been disabled.",
    rejectReason: "Rejection reason",
    viewQr: "View QR code",
    qrTitle: "QR code",
    copied: "Copied.",
    type: {
      PAYPAL: "PayPal",
      USDT: "USDT"
    },
    network: {
      TRC20: "TRC20",
      ERC20: "ERC20",
      BEP20: "BEP20",
      SOL: "SOL",
      TON: "TON"
    },
    status: {
      PENDING: "Under review",
      APPROVED: "Approved",
      REJECTED: "Rejected",
      DISABLED: "Disabled"
    },
    form: {
      type: "Type",
      typeRequired: "Choose a type first.",
      account: "PayPal email / USDT address",
      accountPaypalHint: "Your PayPal email (up to 128 characters)",
      accountUsdtHint: "Your wallet address, letters and digits only",
      accountRange: "Enter 3–128 characters.",
      accountPattern: "The address may contain letters and digits only.",
      holderRange: "Enter the receiver name (1–64 characters).",
      currency: "Currency",
      currencyRequired: "Choose the currency.",
      network: "Network",
      networkRequired: "Choose the network.",
      qr: "QR code (optional)",
      qrAdd: "Upload QR code",
      qrRemove: "Remove",
      qrHint: "PNG or JPEG, up to 2 MB.",
      submit: "Submit"
    }
  },
  register: {
    title: "Creator registration",
    subtitle: "Complete your profile to join the invited workspace",
    invitedBy: "Invited by {name}",
    invalidLink:
      "This registration link is invalid or has expired. Please contact your representative for a new link.",
    emailExists: "This email is already registered. Please sign in.",
    goToLogin: "Sign in",
    inviteCode: "Invitation code",
    inviteCodeRequired: "Enter the invitation code.",
    inviteCodeReadonly: "Filled from your invitation link",
    codeInvalid: "Enter a valid 8-character invite code.",
    invalidCode: "This invite code is invalid. Check it and try again.",
    tryLater: "Too many attempts. Please try again later.",
    name: "Name",
    nameRequired: "Enter your account name (up to 64 characters).",
    socialPairRequired: "Enter both a primary platform and account name, or leave both blank.",
    whatsapp: "WhatsApp (optional)",
    whatsappInvalid: "Enter a WhatsApp number starting with + and 8–15 digits.",
    phone: "Phone number (optional)",
    phoneInvalid: "Enter a phone number starting with + and 8–15 digits.",
    platformLabel: "Main platform (optional)",
    platformPlaceholder: "Select a platform",
    platforms: {
      TikTok: "TikTok",
      Instagram: "Instagram",
      YouTube: "YouTube",
      X: "X",
      Facebook: "Facebook",
      Other: "Other"
    },
    handle: "Social account",
    submit: "Create creator account"
  },
  copy: {
    done: "Copied {text}",
    failed: "Could not copy. Select the text and copy it manually."
  },
  search: {
    expand: "Expand",
    collapse: "Collapse",
    chip: "{label}: {text}",
    clearAll: "Clear all"
  },
  table: {
    total: "{total} in total",
    range: "{from}–{to} of {total}",
    densityDefault: "Default",
    densityCompact: "Compact",
    densityLoose: "Loose",
    refresh: "Refresh",
    columns: "Columns",
    hidden: "{n} hidden",
    moveUp: "Move up",
    moveDown: "Move down",
    reset: "Reset",
    fullscreen: "Table full screen",
    exitFullscreen: "Exit table full screen",
    empty: "No data",
    emptyFiltered: "No matching data · {n} filters applied",
    clearFilters: "Clear filters",
    export: "Export",
    selected: "{n} selected",
    selectedPageOnly: "current page only",
    clearSelection: "Clear selection"
  },
  errors: {
    PREPAY_REQUEST_CONFLICT:
      "This request ID was used for different data. Check the application list before retrying.",
    PREPAY_STATE_CONFLICT: "The prepay task has changed. Refresh before continuing.",
    PREPAY_DETAILS_REQUIRED:
      "Complete the application details in the pending finance task before approval.",
    PREPAY_PAYMENT_CONFLICT:
      "Payment is already recorded or this payment ID has different data. Check the payment result.",
    PREPAY_FILE_INVALID: "Prepay attachments and vouchers must be valid PNG, JPEG or PDF files.",
    PREPAY_FILE_TOO_LARGE: "Prepay attachments and vouchers must not exceed 10 MB.",
    COMMON_VALIDATION_FAILED:
      "Some of the information you entered is not valid. Please check and try again.",
    COMMON_NOT_FOUND: "The requested item does not exist.",
    COMMON_METHOD_NOT_ALLOWED: "This action is not supported.",
    COMMON_UNSUPPORTED_MEDIA_TYPE: "This request format is not supported.",
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
    PAYOUT_ACCOUNT_LIMIT: "The configured payout account limit has been reached.",
    PAYOUT_ACCOUNT_LOCKED: "Approved accounts cannot be changed. Add a new one instead.",
    PAYOUT_ACCOUNT_NOT_APPROVED: "Only approved accounts can be the default.",
    FILE_INVALID: "The file must be a PNG or JPEG image.",
    FILE_TOO_LARGE: "The file is larger than 2 MB.",
    FILE_IN_USE: "This file is already used by another account.",
    KOL_EMAIL_EXISTS: "This email is already registered. Please sign in.",
    KOL_ALREADY_CLAIMED: "This creator has already been claimed by another BD",
    KOL_PROMOTION_CONFIG_INCOMPLETE: "Promotion config is incomplete for PROMOTING status",
    KOL_REGISTER_CODE_INVALID:
      "This registration link is invalid or has expired. Please contact your representative for a new link.",
    KOL_REGISTER_TOO_FREQUENT: "Too many registration attempts. Please try again later.",
    generic: "The operation failed. Please try again later.",
    network: "Network error. Please check your connection and try again.",
    field: "This field is not valid."
  }
};

export default enUS;
export type MessageSchema = typeof enUS;
