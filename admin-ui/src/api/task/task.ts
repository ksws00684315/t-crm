import { http } from "../http";
import type { DownloadedFile } from "@/utils/http";
import type { PageResult, TaskStatus, TaskType, TaskVO } from "../types";

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

/**
 * `ImportTaskVO`: one row of the "Uploads" list (task 33 §3.5). Row counts are strings the same
 * way ids are; `hasErrorFile` drives the download-error-file action.
 */
export interface ImportTaskVO {
  id: string;
  type: TaskType;
  fileName: string | null;
  status: TaskStatus;
  totalRows: string;
  successRows: string;
  failedRows: string;
  createTime: string;
  finishTime: string | null;
  operatorName: string | null;
  hasErrorFile: boolean;
}

export interface ImportTaskQuery {
  type?: TaskType;
  status?: TaskStatus;
}

/** Super administrators see the whole company; everyone else only their own submissions. */
export function listImportTasks(
  page: number,
  size: number,
  query: ImportTaskQuery = {}
): Promise<PageResult<ImportTaskVO>> {
  return http.request<PageResult<ImportTaskVO>>({
    method: "get",
    url: "/task/tasks",
    params: { page, size, ...query }
  });
}
