export type UnauthorizedAction = "ignore" | "login" | "reauth";

/**
 * 401 while a session is still on screen opens the reauth dialog.
 * A disabled account or a signed-out caller still goes to the login page.
 * AUTH_ACCOUNT_LOCKED is HTTP 429 from the backend, so the 401 handler never sees it;
 * ReauthDialog handles that code on its own submit path. The branch below is defense in depth.
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
