import { http } from "./http";

type Count = number | string;

interface Queue<T> {
  total: Count;
  rows: T[];
}

export interface Rank {
  id: Count;
  name: string | null;
  code: string | null;
  quantity: Count;
  amount: string;
}

export interface Workbench {
  businessDate: string;
  asOf: string;
  sales?: { totalAmount: string; totalOrders: Count; todayAmount: string; todayOrders: Count };
  commission?: { totalAmount: string | null; todayAmount: string | null };
  topShops?: Rank[];
  topCreators?: Rank[];
  shops?: {
    enabled: Count;
    authorized: Count;
    authExceptions: Count;
    used: Count | null;
    limit: Count | null;
    remaining: Count | null;
  };
  processingJobs?: Count | null;
  jobs?: Queue<{
    jobCode: string;
    kolName: string | null;
    successCount: number;
    totalCount: number;
    status: string;
  }>;
  creators?: { visible: Count; today: Count };
  promoting?: Count | null;
  prepays?: Count | null;
  pending?: Queue<{
    code: string;
    name: string | null;
    inviter: string | null;
    createdAt: string;
  }>;
  payouts?: Queue<{ applicationNo: string; amount: string; status: string; paused: boolean }>;
  unavailable: string[];
}

export type RankBoard = "SHOP" | "CREATOR";

export const getWorkbenchRanks = (dimension: RankBoard, page = 1) =>
  http.request<{ items: Rank[]; total: Count; page: number; size: number }>({
    method: "get",
    url: "/workbench/rankings",
    params: { dimension, page }
  });

export const getWorkbench = () =>
  http.request<Workbench>({ method: "get", url: "/workbench/overview" });
