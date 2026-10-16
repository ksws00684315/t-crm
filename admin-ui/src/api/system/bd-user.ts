import { http } from "../http";
import type { BdUserVO } from "../types";

/** Selectable BDs. The list is loaded only when the screen offers a BD filter or picker. */
export function listBdUsers(): Promise<BdUserVO[]> {
  return http.request<BdUserVO[]>({ method: "get", url: "/system/bd-users" });
}
