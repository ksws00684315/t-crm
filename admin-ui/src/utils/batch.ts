/**
 * Batch actions run the existing single-row API one row at a time (research report P0-2): no new
 * backend endpoint, the same permission checks per row, and partial failures are reported rather
 * than hidden. Sequential on purpose so the audit log and rate limits see ordinary traffic.
 */
export interface BatchFailure<T> {
  item: T;
  error: unknown;
}

export interface BatchResult<T> {
  succeeded: T[];
  failed: BatchFailure<T>[];
}

export async function runBatch<T>(
  items: readonly T[],
  action: (item: T) => Promise<unknown>,
  onProgress?: (done: number, total: number) => void
): Promise<BatchResult<T>> {
  const succeeded: T[] = [];
  const failed: BatchFailure<T>[] = [];
  let done = 0;
  for (const item of items) {
    try {
      await action(item);
      succeeded.push(item);
    } catch (error) {
      failed.push({ item, error });
    }
    done += 1;
    onProgress?.(done, items.length);
  }
  return { succeeded, failed };
}
