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
    account: "账号",
    password: "密码",
    submit: "登录",
    hint: "达人登录将在后续版本开放。"
  },
  home: {
    title: "欢迎",
    body: "门户正在建设中，您的销售、佣金与提现概览将在此展示。"
  },
  route: {
    login: "登录",
    home: "首页",
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
    AUTH_INVALID_CREDENTIALS: "账号或密码错误。",
    AUTH_ACCOUNT_LOCKED: "登录失败次数过多，请稍后再试。",
    AUTH_ACCOUNT_DISABLED: "该账号已被停用或不可用。",
    generic: "操作失败，请稍后重试。",
    network: "网络异常，请检查网络连接后重试。",
    field: "该字段格式不正确。"
  }
};

export default zhCN;
