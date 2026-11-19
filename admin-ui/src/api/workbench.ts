import { http } from "./http";

type Count = number | string;

interface Queue<T> {
  total: Count;
  rows: T[];
}

interface Rank {
  name: string | null;
  code: string | null;
  quantity: Count;
  amount: string;
}

export interface Workbench {
  businessDate: string;
  asOf: string;
  sales?: { amount: string; orders: Count };
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
  activeLinks?: Count | null;
  activeTokens?: Count | null;
  jobs?: Queue<{
    jobCode: string;
    kolName: string | null;
    successCount: number;
    totalCount: number;
    status: string;
  }>;
  creators?: { visible: Count; approved: Count };
  pending?: Queue<{
    code: string;
    name: string | null;
    inviter: string | null;
    createdAt: string;
  }>;
  payouts?: Queue<{ applicationNo: string; amount: string; status: string; paused: boolean }>;
  estimatedCommission?: string | null;
  unavailable: string[];
}

export const getWorkbench = () =>
  http.request<Workbench>({ method: "get", url: "/workbench/overview" });
