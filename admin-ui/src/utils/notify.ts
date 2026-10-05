import { ElMessage } from "element-plus";
import { ApiError } from "@/utils/http/error";

/** Shows an API failure (or any unexpected error) to the user in Chinese. */
export function notifyError(error: unknown): void {
  if (error instanceof ApiError) {
    // A 401 away from the login page was already announced by the redirect handler.
    if (!error.handled) ElMessage.error(error.displayMessage);
    return;
  }
  ElMessage.error("操作失败，请稍后重试");
}
