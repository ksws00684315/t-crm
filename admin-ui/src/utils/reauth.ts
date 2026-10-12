export type UnauthorizedAction = "ignore" | "login" | "reauth";

/**
 * 401 while a session is still on screen opens the reauth dialog.
 * A disabled account, a lockout from the dialog's own login, or a signed-out caller still
 * goes to the login page. A wrong password while the dialog is open stays on the dialog.
 */
export function decideUnauthorized(input: {
  onLoginPage: boolean;
  loggedIn: boolean;
  errorCode: string | null;
  dialogOpen: boolean;
}): UnauthorizedAction {
  if (input.onLoginPage) return "ignore";
  if (!input.loggedIn || input.errorCode === "AUTH_ACCOUNT_DISABLED") return "login";
  if (input.errorCode === "AUTH_ACCOUNT_LOCKED" && input.dialogOpen) return "login";
  if (input.errorCode === "AUTH_UNAUTHORIZED") return input.dialogOpen ? "ignore" : "reauth";
  if (input.dialogOpen) return "ignore";
  return "login";
}
