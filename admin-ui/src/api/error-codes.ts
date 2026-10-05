/**
 * Maps the backend `ErrorCode` registry to Chinese user-facing text. The backend `msg` is never
 * shown. `error-codes.spec.ts` fails when the backend enum gains a value that is missing here.
 */
export const ERROR_MESSAGES: Readonly<Record<string, string>> = {
  COMMON_VALIDATION_FAILED: "提交的内容不符合要求，请检查后重试",
  COMMON_NOT_FOUND: "请求的内容不存在",
  COMMON_METHOD_NOT_ALLOWED: "不支持该操作",
  COMMON_CONFLICT: "数据冲突，请刷新后重试",
  COMMON_INTERNAL_ERROR: "服务器开小差了，请稍后重试",
  AUTH_UNAUTHORIZED: "登录已失效，请重新登录",
  AUTH_FORBIDDEN: "没有权限执行该操作",
  AUTH_INVALID_CREDENTIALS: "用户名或密码错误",
  AUTH_ACCOUNT_LOCKED: "登录失败次数过多，请稍后再试",
  AUTH_ACCOUNT_DISABLED: "账号已被停用或不可用，请联系管理员",
  AUTH_OLD_PASSWORD_INCORRECT: "原密码不正确",
  USER_USERNAME_EXISTS: "用户名已存在",
  USER_CANNOT_DISABLE_SELF: "不能停用自己的账号",
  USER_CANNOT_REMOVE_SELF_ROLE: "不能取消自己的超级管理员角色",
  USER_INVALID_DATA_SCOPE: "“仅本人数据”范围只适用于运营角色",
  TASK_QUEUE_FULL: "当前排队的任务过多，请稍后再试",
  TASK_FILE_INVALID: "文件格式不正确，请上传 .xlsx 格式的 Excel 文件",
  TASK_FILE_TOO_LARGE: "文件超过 20MB，请拆分后再上传",
  TASK_FILE_TOO_MANY_ROWS: "数据行数超过上限（2000 行），请拆分后再上传",
  TASK_FILE_HEADER_MISMATCH: "表头与模板不一致，请下载最新模板后填写",
  TASK_FILE_EMPTY: "文件中没有数据行"
};

export const GENERIC_ERROR_MESSAGE = "操作失败，请稍后重试";
export const NETWORK_ERROR_MESSAGE = "网络异常，请检查网络连接后重试";
export const FIELD_ERROR_MESSAGE = "该字段格式不正确";

/** Chinese text for an error code; unknown or missing codes get the generic text. */
export function messageForErrorCode(errorCode: string | null | undefined): string {
  return errorCode && isKnownErrorCode(errorCode)
    ? ERROR_MESSAGES[errorCode]
    : GENERIC_ERROR_MESSAGE;
}

export function isKnownErrorCode(errorCode: string | null | undefined): boolean {
  return !!errorCode && Object.prototype.hasOwnProperty.call(ERROR_MESSAGES, errorCode);
}
