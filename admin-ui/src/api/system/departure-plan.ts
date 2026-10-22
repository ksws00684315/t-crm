import { http } from "../http";
import type { DeparturePlanType, DeparturePlanVO } from "../types";

/** `DeparturePlanRequest`. An empty `effectiveAt` runs the plan immediately. */
export interface DeparturePlanRequest {
  type: DeparturePlanType;
  effectiveAt?: string | null;
  heirUserId?: string | null;
  reason?: string | null;
}

/** One selectable heir (`DepartureHeirVO`). */
export interface DepartureHeirVO {
  id: string;
  username: string;
  nickname: string;
}

/** BDs that may inherit creators; the departing account itself is excluded. */
export function listDepartureHeirs(excludeUserId?: string): Promise<DepartureHeirVO[]> {
  return http.request<DepartureHeirVO[]>({
    method: "get",
    url: "/system/users/departure-heirs",
    params: excludeUserId ? { excludeUserId } : {}
  });
}

/** The pending plan of one account; null when it has none. */
export function getDeparturePlan(userId: string): Promise<DeparturePlanVO | null> {
  return http.request<DeparturePlanVO | null>({
    method: "get",
    url: `/system/users/${userId}/departure-plan`
  });
}

export function createDeparturePlan(
  userId: string,
  request: DeparturePlanRequest
): Promise<DeparturePlanVO> {
  return http.request<DeparturePlanVO>({
    method: "post",
    url: `/system/users/${userId}/departure-plan`,
    data: request
  });
}

export function updateDeparturePlan(
  userId: string,
  request: DeparturePlanRequest
): Promise<DeparturePlanVO> {
  return http.request<DeparturePlanVO>({
    method: "put",
    url: `/system/users/${userId}/departure-plan`,
    data: request
  });
}

export function cancelDeparturePlan(userId: string): Promise<null> {
  return http.request<null>({
    method: "delete",
    url: `/system/users/${userId}/departure-plan`
  });
}
