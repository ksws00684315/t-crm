import { http } from "../http";
import type { BdUserVO } from "../types";

/** Selectable BDs; only SUPER_ADMIN and OPERATOR + ALL may call (a BD gets 403). */
export function listBdUsers(): Promise<BdUserVO[]> {
  return http.request<BdUserVO[]>({ method: "get", url: "/system/bd-users" });
}
