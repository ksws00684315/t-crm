import { http } from "../http";

/** Starts a full link index rebuild (202); LINK_REBUILD_RUNNING (409) while one is running. */
export function rebuildLinkIndex(): Promise<null> {
  return http.request<null>({ method: "post", url: "/link/index-rebuilds" });
}
