import type { MessageSchema } from "./en-US";

/**
 * Simplified Chinese messages for overseas creators: concise and formal. "Creator" is 达人,
 * "Sign in" is 登录, "Sign out" is 退出登录 (terminology from task 06d). The type forces the keys
 * to match en-US exactly; values are the translations.
 */
const zhCN: MessageSchema = {
  app: {
    title: "T-CRM Creator Portal"
  },
  common: {
    language: "语言",
    signOut: "退出登录",
    backToHome: "返回首页",
    comingSoon: "即将上线",
    cancel: "取消",
    edit: "修改",
    copy: "复制"
  },
  /** 页头导航（任务 15b §4.3）；键由 `layout/nav.ts` 引用。 */
  nav: {
    label: "主导航",
    home: "首页",
    payoutAccounts: "收款账户",
    orders: "我的订单"
  },
  locale: {
    "en-US": "English",
    "zh-CN": "中文"
  },
  login: {
    title: "T-CRM Creator Portal",
    account: "邮箱",
    password: "密码",
    submit: "登录",
    forgot: "忘记密码？",
    selectCompany: "选择公司",
    chooseCompany: "请选择一家公司。",
    continue: "继续"
  },
  auth: {
    email: "邮箱",
    emailInvalid: "请输入有效的邮箱地址。",
    passwordRule: "密码须为 10～72 位可打印 ASCII 字符。",
    passwordMismatch: "两次输入的密码不一致。",
    newPassword: "新密码",
    confirmPassword: "确认密码",
    backToLogin: "返回登录",
    activateTitle: "激活账号",
    activateSubmit: "激活并登录",
    forgotTitle: "找回密码",
    sendReset: "发送重置邮件",
    forgotSent: "如果该邮箱已注册，我们已发送重置邮件。",
    resetTitle: "重置密码",
    resetSubmit: "重置密码",
    resetSuccess: "密码已重置，请登录。"
  },
  account: {
    title: "我的账号",
    kolCode: "达人编号",
    name: "名称",
    email: "邮箱",
    country: "国家",
    tenantName: "公司",
    unavailable: "未填写",
    sheet: "推广表格",
    openSheet: "打开推广表格",
    changePassword: "修改密码",
    oldPassword: "当前密码",
    passwordChanged: "密码已修改。",
    retry: "重试"
  },
  home: {
    title: "欢迎",
    body: "门户正在建设中，您的销售、佣金与提现概览将在此展示。",
    payoutEntry: "收款账户"
  },
  route: {
    login: "登录",
    home: "首页",
    activate: "激活账号",
    reset: "重置密码",
    forgot: "找回密码",
    account: "我的账号",
    payoutAccounts: "收款账户",
    register: "创建账号",
    notFound: "页面不存在"
  },
  payoutAccounts: {
    title: "收款账户",
    create: "添加收款账户",
    createTitle: "添加收款账户",
    editTitle: "修改收款账户",
    empty: "添加 PayPal 或 USDT 账户，审核通过后用于收款。",
    limitReached: "收款账户最多保留 5 个（不含已停用）。",
    submitted: "已提交，等待商务审核。",
    account: "账号 / 地址",
    holder: "收款人",
    submittedAt: "提交时间",
    defaultTag: "默认",
    setDefault: "设为默认",
    defaultSet: "已将该账户设为默认。",
    disable: "停用",
    disableConfirm: "停用后该账户不能恢复，也不会再显示。",
    disabled: "账户已停用。",
    rejectReason: "驳回原因",
    viewQr: "查看二维码",
    qrTitle: "收款二维码",
    copied: "已复制。",
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
      PENDING: "待审核",
      APPROVED: "已通过",
      REJECTED: "已驳回",
      DISABLED: "已停用"
    },
    form: {
      type: "类型",
      typeRequired: "请先选择类型。",
      account: "PayPal 账号 / USDT 地址",
      accountPaypalHint: "PayPal 账号（通常是邮箱，最多 128 字符）",
      accountUsdtHint: "钱包地址，仅字母和数字",
      accountRange: "请输入 3～128 个字符。",
      accountPattern: "地址只能包含字母和数字。",
      holderRange: "请填写收款人（1～64 字符）。",
      network: "网络",
      networkRequired: "请选择网络。",
      qr: "二维码（可选）",
      qrAdd: "上传二维码",
      qrRemove: "移除",
      qrHint: "PNG 或 JPEG，不超过 2MB。",
      submit: "提交"
    }
  },
  register: {
    title: "创建账号",
    join: "加入 {company}",
    invalidLink: "注册链接无效或已失效，请联系您的商务获取新链接",
    emailExists: "该邮箱已注册，请直接登录",
    goToLogin: "去登录",
    sendCode: "发送验证码",
    resendIn: "{seconds} 秒后可重发",
    emailCode: "验证码",
    emailCodeInvalid: "请输入 6 位验证码。",
    whatsapp: "WhatsApp",
    whatsappInvalid: "请输入以 + 开头、后接 8～15 位数字的 WhatsApp 号码。",
    platformLabel: "主阵地平台",
    platformPlaceholder: "请选择平台",
    platformRequired: "请选择主阵地平台。",
    platforms: {
      TikTok: "TikTok",
      Instagram: "Instagram",
      YouTube: "YouTube",
      X: "X",
      Facebook: "Facebook",
      Other: "其他"
    },
    handle: "账号名",
    handleRequired: "请填写账号名（最多 64 个字符）。",
    profileUrl: "主页链接（可选）",
    urlInvalid: "主页链接必须以 https:// 开头。",
    more: "更多（可选）",
    name: "名称",
    country: "国家",
    countryInvalid: "请填写两位国家代码，或留空。",
    submit: "创建账号"
  },
  errors: {
    COMMON_VALIDATION_FAILED: "提交的内容不符合要求，请检查后重试。",
    COMMON_NOT_FOUND: "请求的内容不存在。",
    COMMON_METHOD_NOT_ALLOWED: "不支持该操作。",
    COMMON_CONFLICT: "数据已变更，请刷新后重试。",
    COMMON_INTERNAL_ERROR: "服务器出现异常，请稍后重试。",
    AUTH_UNAUTHORIZED: "登录已失效，请重新登录。",
    AUTH_FORBIDDEN: "没有权限执行该操作。",
    AUTH_INVALID_CREDENTIALS: "邮箱或密码错误。",
    AUTH_ACCOUNT_LOCKED: "登录失败次数过多，请稍后再试。",
    AUTH_ACCOUNT_DISABLED: "该账号已被停用或不可用。",
    KOL_AUTH_TOKEN_INVALID: "链接已失效，请联系您的商务重新发送。",
    AUTH_OLD_PASSWORD_INCORRECT: "当前密码错误。",
    KOL_LOGIN_SELECTION_INVALID: "公司选择凭据已失效，请重新登录。",
    PAYOUT_ACCOUNT_LIMIT: "收款账户最多保留 5 个（不含已停用）。",
    PAYOUT_ACCOUNT_LOCKED: "已通过的账户不能修改，请新建一个。",
    PAYOUT_ACCOUNT_NOT_APPROVED: "只有已通过的账户才能设为默认。",
    FILE_INVALID: "文件必须是 PNG 或 JPEG 图片。",
    FILE_TOO_LARGE: "文件超过 2MB。",
    FILE_IN_USE: "该文件已被其他账户使用。",
    KOL_EMAIL_EXISTS: "该邮箱已注册，请直接登录",
    KOL_REGISTER_CODE_INVALID: "注册链接无效或已失效，请联系您的商务获取新链接",
    KOL_EMAIL_CODE_TOO_FREQUENT: "验证码发送过于频繁，请稍后再试",
    KOL_EMAIL_CODE_INVALID: "验证码错误、已过期或已使用",
    KOL_REGISTER_TOO_FREQUENT: "注册尝试过于频繁，请稍后再试",
    generic: "操作失败，请稍后重试。",
    network: "网络异常，请检查网络连接后重试。",
    field: "该字段格式不正确。"
  }
};

export default zhCN;
