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
    comingSoon: "即将上线"
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
    body: "门户正在建设中，您的销售、佣金与提现概览将在此展示。"
  },
  route: {
    login: "登录",
    home: "首页",
    activate: "激活账号",
    reset: "重置密码",
    forgot: "找回密码",
    account: "我的账号",
    notFound: "页面不存在"
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
    generic: "操作失败，请稍后重试。",
    network: "网络异常，请检查网络连接后重试。",
    field: "该字段格式不正确。"
  }
};

export default zhCN;
