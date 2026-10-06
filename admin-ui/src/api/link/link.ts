import { http } from "../http";
import type { LinkVO, PageResult } from "../types";

/** `LinkQuery`: `kolCode` is required; `productCode` is an exact original product code. */
export interface LinkQuery {
  kolCode: string;
  productCode?: string;
  page?: number;
  size?: number;
}

/** Links of one KOL ordered by original product code, with 30-day click counts. */
export function listLinks(query: LinkQuery): Promise<PageResult<LinkVO>> {
  return http.request<PageResult<LinkVO>>({ method: "get", url: "/link/links", params: query });
}

/** Starts a full link index rebuild (202); LINK_REBUILD_RUNNING (409) while one is running. */
export function rebuildLinkIndex(): Promise<null> {
  return http.request<null>({ method: "post", url: "/link/index-rebuilds" });
}
