import type { MessageSchema } from "./zh-CN";

/** English pack. The type is the Chinese base pack, so a missing or extra key fails typecheck. */
const enUS: MessageSchema = {
  app: {
    title: "T-CRM Admin"
  },
  common: {
    language: "Language",
    search: "Search",
    reset: "Reset",
    cancel: "Cancel",
    save: "Save",
    confirm: "Confirm",
    close: "Close",
    edit: "Edit",
    create: "Create",
    delete: "Delete",
    copy: "Copy",
    detail: "Details",
    actions: "Actions",
    status: "Status",
    remark: "Remark",
    all: "All",
    enabled: "Enabled",
    disabled: "Disabled",
    email: "Email",
    username: "Username",
    nickname: "Nickname",
    password: "Password",
    signIn: "Sign in",
    signOut: "Sign out",
    backToHome: "Back to home",
    createdAt: "Created at",
    updatedAt: "Updated at",
    keyword: "Keyword",
    country: "Country",
    name: "Name",
    platformScope: "Platform",
    unassigned: "Unassigned"
  },
  locale: {
    "en-US": "English",
    "zh-CN": "中文"
  },
  role: {
    PLATFORM_ADMIN: "Platform Admin",
    SUPER_ADMIN: "Super Admin",
    STAFF: "Staff"
  },
  post: {
    OPERATOR: "Operator",
    BD: "BD",
    none: "—"
  },
  permissionGroup: {
    KOL: "Creators",
    PRODUCT: "Products",
    ORDER: "Orders",
    FUNDS: "Funds",
    DASHBOARD: "Dashboards",
    OTHER: "Other"
  },
  permission: {
    "kol:view": {
      name: "View creators",
      desc: "View the creator list, details, and BD history"
    },
    "kol:edit": {
      name: "Edit creators",
      desc: "Create, edit, enable or disable creators, set rates and purchasing details, and import"
    },
    "kol:register-link": {
      name: "Registration links",
      desc: "Manage your own creator registration links"
    },
    "kol:copy-token": {
      name: "Copy tokens",
      desc: "Issue or revoke a creator's copy token"
    },
    "product:view": {
      name: "View products",
      desc: "View shops, source products, mappings, and tracking links"
    },
    "product:edit": {
      name: "Edit products",
      desc: "Import and edit source products, and import mappings"
    },
    "product:status": {
      name: "Shelf status",
      desc: "Put source products on or off the shelf"
    },
    "order:view": {
      name: "View orders",
      desc: "View orders and line items"
    },
    "order:manage": {
      name: "Manage orders",
      desc: "Import orders, sync them manually, and match them by hand"
    },
    "commission:view": {
      name: "View commission",
      desc: "View the commission ledger, balances, withdrawals, and prepay"
    },
    "commission:freeze": {
      name: "Freeze withdrawals",
      desc: "Freeze or unfreeze a creator's withdrawals"
    },
    "payout-account:review": {
      name: "Review payout accounts",
      desc: "Approve or reject a creator's payout account"
    },
    "withdrawal:review": {
      name: "Review withdrawals",
      desc: "Approve or reject a creator's withdrawal request"
    },
    "prepay:apply": {
      name: "Apply for prepay",
      desc: "Submit or withdraw a prepay request for a creator"
    },
    "dashboard:shop": {
      name: "Shop dashboard",
      desc: "View the shop and product dashboards"
    },
    "dashboard:team": {
      name: "Team dashboard",
      desc: "View the BD dashboard"
    },
    "dashboard:kol": {
      name: "Creator dashboard",
      desc: "View a creator's dashboard on their behalf"
    },
    "data:export": {
      name: "Export data",
      desc: "Export lists you are allowed to view"
    }
  },
  scope: {
    ALL: "All data",
    OWN: "Only creators I manage",
    allShort: "All",
    ownShort: "Own only"
  },
  route: {
    login: "Sign in",
    home: "Home",
    tenants: "Companies",
    kols: "Creators",
    products: "Products",
    shops: "Shops",
    product: "Product",
    mappings: "Mappings",
    users: "Users",
    auditLogs: "Audit Log",
    password: "Change Password",
    forbidden: "Access denied",
    notFound: "Page not found"
  },
  errors: {
    COMMON_VALIDATION_FAILED: "Some of the information is not valid. Please check and try again.",
    COMMON_NOT_FOUND: "The requested item does not exist.",
    COMMON_METHOD_NOT_ALLOWED: "This action is not supported.",
    COMMON_CONFLICT: "The data conflicts with existing records. Please refresh and try again.",
    COMMON_INTERNAL_ERROR: "Something went wrong on our side. Please try again later.",
    AUTH_UNAUTHORIZED: "Your session has expired. Please sign in again.",
    AUTH_FORBIDDEN: "You do not have permission to do this.",
    AUTH_INVALID_CREDENTIALS: "Incorrect username or password.",
    AUTH_ACCOUNT_LOCKED: "Too many failed sign-in attempts. Please try again later.",
    AUTH_CAPTCHA_INVALID: "The captcha is wrong or has expired. Please enter the new one.",
    AUTH_CAPTCHA_RATE_LIMITED: "Too many captcha requests. Please try again later.",
    AUTH_ACCOUNT_DISABLED: "This account is disabled or unavailable. Contact an administrator.",
    AUTH_OLD_PASSWORD_INCORRECT: "The current password is incorrect.",
    USER_USERNAME_EXISTS: "This username already exists.",
    TENANT_CODE_EXISTS: "This company code already exists.",
    USER_CANNOT_DISABLE_SELF: "You cannot disable your own account.",
    USER_CANNOT_REMOVE_SELF_ROLE: "You cannot remove your own Super Admin role.",
    USER_INVALID_DATA_SCOPE: "Own Data Only applies only to a staff account.",
    USER_PERMISSION_INVALID: "Those permissions are not valid. Choose them again.",
    USER_LAST_SUPER_ADMIN: "At least one enabled Super Admin must remain.",
    KOL_EMAIL_EXISTS: "This email is already used by another creator.",
    KOL_CODE_EXISTS: "This creator code already exists.",
    KOL_ALREADY_ACTIVATED: "This creator has already activated the account.",
    KOL_AUTH_TOKEN_INVALID: "The invitation or reset link is invalid, expired, or already used.",
    KOL_LOGIN_SELECTION_INVALID: "The company selection credential is invalid or expired.",
    KOL_INVALID_BD_USER:
      "BD must be an enabled staff account with Own Data Only and creator-edit permission.",
    KOL_MULTIPLE_PRIMARY: "At most one social account can be the primary one.",
    LINK_TEMPLATE_INVALID: "The agent template must be an enabled template of this company.",
    LINK_TEMPLATE_NOT_FOUND: "The agent template was not found.",
    LINK_TEMPLATE_CODE_EXISTS: "This template code already exists.",
    TASK_QUEUE_FULL: "Too many tasks are waiting. Please try again later.",
    TASK_FILE_INVALID: "The file format is not valid. Upload an .xlsx Excel file.",
    TASK_FILE_TOO_LARGE: "The file is larger than 20MB. Split it and upload again.",
    TASK_FILE_TOO_MANY_ROWS: "The file has too many rows. Split it and upload again.",
    TASK_FILE_HEADER_MISMATCH:
      "The header does not match the template. Download the latest template.",
    TASK_FILE_EMPTY: "The file has no data rows.",
    TASK_FILE_EXPIRED: "The error file is older than 30 days and has been removed.",
    SHOP_LIMIT_REACHED: "The shop limit has been reached (at most 3).",
    SHOP_WEIDIAN_ID_EXISTS: "This Weidian shop ID already exists.",
    WEIDIAN_APP_NOT_CONFIGURED: "The Weidian service app is not configured.",
    WEIDIAN_SHOP_NOT_AUTHORIZED: "The Weidian shop is not authorized.",
    WEIDIAN_CREDENTIAL_INVALID: "The Weidian appkey or secret is invalid.",
    WEIDIAN_API_FAILED: "The Weidian API request failed. Try again later.",
    WEIDIAN_RATE_LIMITED: "The Weidian API rate limit was reached. Try again later.",
    SHOP_DISABLED: "The shop is disabled and this action cannot be done.",
    SHOP_NOT_FOUND: "Shop not found.",
    PRODUCT_NOT_FOUND: "Product not found.",
    PRODUCT_CODE_EXISTS: "This product code already exists.",
    PRODUCT_WEIDIAN_ID_EXISTS: "This Weidian item ID already exists in the shop.",
    PRODUCT_CODE_IMMUTABLE: "The product code cannot be changed after it is created.",
    PRODUCT_IS_EXCLUSIVE_ITEM: "This Weidian item ID is an exclusive item and cannot be a product.",
    ORDER_SYNC_RUNNING: "Order synchronization is already running for this shop.",
    LINK_REBUILD_RUNNING: "A tracking-link index rebuild is already running. Try again later.",
    generic: "The operation failed. Please try again later.",
    network: "Network error. Check your connection and try again.",
    field: "This field is not valid.",
    withTrace: "{message} (trace id: {traceId})"
  },
  validation: {
    usernameRequired: "Enter a username.",
    usernamePattern: "Username must be 3-64 letters, digits, underscores, dots, or hyphens.",
    passwordRequired: "Enter a password.",
    captchaRequired: "Enter the captcha.",
    passwordLength: "Password must be {min}-{max} characters.",
    passwordCharset: "Password can only contain letters, digits, and common symbols.",
    passwordTooLong: "Password cannot exceed {max} characters.",
    nicknameRequired: "Enter a nickname.",
    nicknameTooLong: "Nickname cannot exceed 64 characters.",
    tenantCodeRequired: "Enter a company code.",
    tenantCodePattern: "Company code must be 2-32 lowercase letters, digits, or hyphens.",
    nameRequired: "Enter a name.",
    nameTooLong: "Name cannot exceed 64 characters.",
    remarkTooLong: "Remark cannot exceed 500 characters.",
    emailTooLong: "Email cannot exceed 254 characters.",
    emailInvalid: "Email format is not valid.",
    kolNameRequired: "Enter a name.",
    kolNameTooLong: "Name cannot exceed 64 characters.",
    kolEmailRequired: "Enter an email.",
    kolEmailTooLong: "Email cannot exceed 128 characters.",
    countryRequired: "Enter a country code.",
    countryPattern: "Enter a two-letter country code, such as US.",
    socialPlatformRequired: "Enter a social platform.",
    socialPlatformTooLong: "Social platform cannot exceed 32 characters.",
    socialHandleRequired: "Enter a social account.",
    socialHandleTooLong: "Social account cannot exceed 128 characters.",
    socialUrl: "Profile URL must start with https:// and be at most 512 characters.",
    followers: "Follower count must be a non-negative integer.",
    percent: "Enter a number from 0 to 100, with at most 2 decimal places.",
    passwordAgain: "Enter the new password again.",
    passwordMismatch: "The two new passwords do not match."
  },
  login: {
    title: "T-CRM Admin",
    username: "Username",
    password: "Password",
    submit: "Sign in"
  },
  captcha: {
    label: "Captcha",
    placeholder: "Enter the characters shown",
    imageAlt: "Captcha image, click for a new one",
    refresh: "Can't read it? Click the image for a new one.",
    loadFailed: "Failed to load. Click to retry."
  },
  home: {
    welcome: "Welcome to T-CRM",
    greeting: "Hello, {nickname}. Current role: {role}.",
    body: "More features will arrive in later releases. Account management is available now."
  },
  layout: {
    changePassword: "Change Password",
    user: "{nickname} ({role})",
    collapse: "Collapse sidebar",
    expand: "Expand sidebar",
    searchMenu: "Search menu",
    refresh: "Refresh current page",
    fullscreen: "Full screen",
    exitFullscreen: "Exit full screen",
    shortcutMac: "⌘ K",
    shortcutOther: "Ctrl K"
  },
  version: {
    message: "The system has been updated. Refresh to use the new version. Open tabs are kept.",
    refresh: "Refresh now",
    later: "Later"
  },
  reauth: {
    title: "Session expired",
    hint: "Enter your password. What you already filled in stays on this page, and the failed request is not sent again.",
    submit: "Sign in again",
    switch: "Switch account",
    failed: "Could not sign in again. Try once more."
  },
  menuSearch: {
    title: "Search menu",
    placeholder: "Menu name",
    recent: "Recent",
    empty: "No matching menus"
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
  export: {
    done: "Exported {n} rows",
    truncated: "{total} results; exported the first {n}. Narrow the filters and export in parts",
    fileKol: "kols",
    fileProduct: "products",
    fileMapping: "kol-items",
    fileUser: "users"
  },
  batch: {
    enable: "Enable selected",
    disable: "Disable selected",
    on: "Put on sale",
    off: "Take off sale",
    confirmEnable: "Enable the {n} selected items?",
    confirmDisable: "Disable the {n} selected items?",
    confirmOn: "Put the {n} selected products on sale?",
    confirmOff: "Take the {n} selected products off sale?",
    nothingToChange: "The selected rows already have that status",
    allDone: "Done: {n} items",
    partial: "{ok} succeeded, {failed} failed: {details}",
    unknown: "Unknown error"
  },
  lock: {
    title: "Screen locked",
    hint: "Signed in as {username}. Enter your password to unlock",
    unlock: "Unlock",
    lockNow: "Lock screen",
    autoLock: "Auto lock when idle",
    idleOff: "Off",
    idleMinutes: "{n} min",
    failed: "Unlock failed, please try again"
  },
  tabs: {
    close: "Close",
    closeOthers: "Close others",
    closeLeft: "Close left",
    closeRight: "Close right",
    closeAll: "Close all",
    refresh: "Refresh current page",
    more: "Tab actions"
  },
  theme: {
    cycle: "Switch theme",
    light: "Light",
    dark: "Dark",
    system: "System"
  },
  password: {
    old: "Current password",
    next: "New password",
    confirm: "Confirm new password",
    submit: "Change Password",
    success: "Password changed. Sign in again with the new password."
  },
  forbidden: {
    subtitle: "You do not have permission to open this page."
  },
  notFound: {
    subtitle: "Page not found"
  },
  user: {
    create: "Create User",
    editTitle: "Edit User",
    createTitle: "Create User",
    role: "Account type",
    post: "Post",
    postTemplate: "Post template",
    permissionCount: "Permissions",
    permissionItems: "{n} items",
    allPermissions: "Has every permission",
    allPermissionsShort: "All",
    permissionsTitle: "Permissions",
    permissionsSaved: "Permissions saved.",
    permissionsSeparate: "Permissions are saved separately from Permissions in the list.",
    currentPost: "Current post: {post}",
    selectAll: "Select all",
    resetTemplate: "Reset from template",
    exclusiveTitle: "Super Admin only. These cannot be granted.",
    exclusiveItems:
      "Users and permissions, system settings, the audit log, prepay approval, marking a withdrawal as paid, and transferring creators.",
    templateRequired: "Choose a post template",
    dataScope: "Data scope",
    lastLogin: "Last sign-in",
    initialPassword: "Initial password",
    resetPassword: "Reset Password",
    resetTitle: "Reset Password: {username}",
    newPassword: "New password",
    saved: "User saved.",
    created: "User created.",
    resetDone: "Password reset. The user must sign in with the new password.",
    confirmDisable: "Disable user {username}?",
    confirmEnable: "Enable user {username}?",
    disableTitle: "Disable user",
    enableTitle: "Enable user",
    disabled: "Disabled",
    enabled: "Enabled"
  },
  tenant: {
    create: "Create Company",
    code: "Company Code",
    name: "Company Name",
    admins: "Super Admin",
    confirmDisable:
      "After the company is disabled, none of its administrators can sign in. Signed-in administrators are signed out on their next action. Tracking links are not affected.",
    confirmEnable: "Enable company {name}?",
    disableTitle: "Disable company",
    enableTitle: "Enable company",
    disabled: "Company disabled.",
    enabled: "Company enabled.",
    editTitle: "Edit Company",
    createTitle: "Create Company",
    codePlaceholder:
      "Lowercase letters, digits, hyphens, 2-32 characters. Cannot be changed later.",
    saved: "Company saved.",
    created: "Company created."
  },
  tenantAdmin: {
    created: "Super Admin created.",
    signedOut: "This administrator has been signed out on every device.",
    title: "Super Admin: {name}",
    resetTitle: "Reset Password: {username}",
    create: "Create Super Admin",
    createTitle: "Create Super Admin",
    lastLogin: "Last sign-in",
    initialPassword: "Initial password",
    resetPassword: "Reset Password",
    newPassword: "New password"
  },
  kol: {
    keywordPlaceholder: "Code / name / email",
    countryPlaceholder: "e.g. US",
    bd: "BD",
    defaultRate: "Default commission",
    create: "Create Creator",
    import: "Bulk Import",
    code: "Creator Code",
    links: "Links",
    changeBd: "Change BD",
    confirmDisable: "Disable creator {code} ({name})?",
    confirmEnable: "Enable creator {code} ({name})?",
    disableTitle: "Disable creator",
    enableTitle: "Enable creator",
    disabled: "Disabled",
    enabled: "Enabled",
    saved: "Creator saved.",
    created: "Creator created.",
    editTitle: "Edit creator {code}",
    createTitle: "Create Creator",
    countryCodePlaceholder: "ISO two-letter code, e.g. US",
    rateLabel: "Default Commission Rate",
    ratePlaceholder: "0-100, optional",
    social: "Social accounts",
    platformPlaceholder: "Platform",
    handlePlaceholder: "Account",
    urlPlaceholder: "Profile URL (https, optional)",
    followersPlaceholder: "Followers (optional)",
    addSocial: "Add social account (up to {max})",
    socialError: "Social account {index}: {message}",
    importTitle: "Bulk Import Creators",
    limitHint: "One file, at most 20MB and 2,000 rows",
    detailTitle: "Creator details",
    followers: " ({count} followers)",
    homepage: "Profile",
    bdHistory: "BD history",
    bdColumn: "BD",
    startedAt: "Start time",
    endedAt: "End time",
    current: "Current",
    changeBdTitle: "Change BD",
    changeBdSummary: "Creator {code} ({name}). Current BD: {bd}",
    changeBdPlaceholder: "Leave empty to mark as unassigned",
    changeBdDone: "BD updated.",
    bdOption: "{nickname} ({username})"
  },
  shop: {
    hint: "At most 3 shops in the whole system. A disabled shop cannot receive product or mapping imports.",
    confirmDisable: 'Disable shop "{name}"?',
    confirmEnable: 'Enable shop "{name}"?',
    disableTitle: "Disable shop",
    enableTitle: "Enable shop",
    saved: "Saved.",
    created: "Created.",
    disabled: "Disabled",
    enabled: "Enabled",
    create: "Create Shop",
    editTitle: "Edit Shop",
    createTitle: "Create Shop",
    name: "Shop Name",
    weidianId: "Weidian Shop ID",
    weidianPlaceholder: "Digits. Cannot be changed after creation."
  },
  product: {
    onSale: "On sale",
    offShelf: "Off shelf",
    putOnShelf: "Put on sale",
    confirmOff: "Take product {code} off the shelf?",
    confirmOn: "Put product {code} on sale?",
    offTitle: "Take off shelf",
    onTitle: "Put on sale",
    doneOff: "Taken off the shelf.",
    doneOn: "Put on sale.",
    saved: "Saved.",
    keywordPlaceholder: "Code / name / Weidian item ID",
    category: "Category",
    importTitle: "Bulk Import Products",
    limitHint: "One file, at most 20MB and 20,000 rows",
    code: "Product Code",
    name: "Product Name",
    shop: "Shop",
    weidianItemId: "Weidian Item ID",
    price: "Price",
    rate: "Commission Rate",
    editTitle: "Edit Product",
    categoryPlaceholder: "Optional",
    image: "Image URL",
    imagePlaceholder: "https://, optional",
    pricePlaceholder: "e.g. 128.50",
    rateLabel: "Commission Rate (%)",
    ratePlaceholder: "0-100, optional"
  },
  mapping: {
    searchTip: "Enter a creator code, product code, or exclusive item ID to search.",
    confirmRebuild:
      "Rebuild the tracking-link index from the current mappings, creators, products, and shop status. This runs in the background. Start now?",
    rebuildTitle: "Rebuild link index",
    rebuildStarted: "Rebuild started.",
    kolCode: "Creator Code",
    productCode: "Product Code",
    shop: "Shop",
    exclusiveId: "Exclusive Item ID",
    includeHistory: "Include history",
    import: "Bulk Import",
    kolName: "Creator Name",
    productName: "Product Name",
    validFrom: "Valid from",
    validTo: "Valid to",
    current: "Currently valid",
    importTitle: "Bulk Import Mappings",
    limitHint: "One file, at most 20MB and 20,000 rows"
  },
  link: {
    reasonKolDisabled: "Creator disabled",
    reasonOffShelf: "Product off shelf",
    reasonShopDisabled: "Shop disabled",
    separator: ", ",
    unavailable: "Unavailable: {reason}",
    available: "Available",
    copied: "Link copied.",
    copyFailed: "Copy failed. Select the link and copy it manually.",
    title: "Tracking Links - {code}",
    titleWithName: "Tracking Links - {code} ({name})",
    productCode: "Product Code",
    exact: "Exact match",
    productName: "Name",
    exclusiveId: "Exclusive Item ID",
    trackingLink: "Tracking Link",
    clicks: "Clicks in 30 days (all / valid)"
  },
  import: {
    waiting: "Waiting in queue…",
    running: "Importing: {done} / {total} rows processed",
    success: "Import finished: {total} rows succeeded",
    partial:
      "Import finished: {succeeded} rows succeeded, {failed} failed. Download the error file for details.",
    failedRows:
      "Import failed: all {failed} rows were skipped. Download the error file for details.",
    failedFile:
      "Import failed: the file could not be processed or the task was interrupted. Check the file and upload again.",
    pollFailed:
      "Progress checks failed repeatedly, so refresh stopped. The task is still running. Close this window and check the list later.",
    xlsxOnly: "Upload an .xlsx Excel file.",
    tooLarge: "The file is larger than 20MB. Split it and upload again.",
    chooseFile: "Choose a file first.",
    stepTemplate: "Download the template and fill in the columns ({limitHint}).",
    downloadTemplate: "Download import template",
    stepUpload: "Choose the completed .xlsx file and upload it. The import runs in the background.",
    downloadErrors: "Download error file",
    closeRunning: "Keep running, close window",
    close: "Close",
    again: "Import another",
    submit: "Upload and import"
  },
  audit: {
    time: "Time",
    operator: "Operator",
    module: "Module",
    action: "Action",
    result: "Result",
    uri: "Request URL",
    ip: "IP",
    cost: "Duration (ms)"
  }
};

export default enUS;
