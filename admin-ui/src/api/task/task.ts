import { http } from "../http";
import type { DownloadedFile } from "@/utils/http";
import type { TaskStatus, TaskVO } from "../types";

/** Statuses after which a task no longer changes. */
export const FINISHED_STATUSES: readonly TaskStatus[] = ["SUCCESS", "PARTIAL", "FAILED"];

export function isFinished(status: TaskStatus): boolean {
  return FINISHED_STATUSES.includes(status);
}

export function getTask(id: string): Promise<TaskVO> {
  return http.request<TaskVO>({ method: "get", url: `/task/tasks/${id}` });
}

export function downloadTaskErrorFile(id: string): Promise<DownloadedFile> {
  return http.download({ method: "get", url: `/task/tasks/${id}/error-file` });
}
