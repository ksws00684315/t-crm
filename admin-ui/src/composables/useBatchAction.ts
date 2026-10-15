import { ElMessage, ElMessageBox } from "element-plus";
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { runBatch } from "@/utils/batch";
import { ApiError } from "@/utils/http/error";

const MAX_LISTED_FAILURES = 3;

/** Confirm, run the per-row API over the current-page selection, then report every outcome. */
export function useBatchAction() {
  const { t } = useI18n();
  const running = ref(false);

  async function run<T>(options: {
    rows: readonly T[];
    title: string;
    message: string;
    label: (row: T) => string;
    action: (row: T) => Promise<unknown>;
    after: () => Promise<void> | void;
  }): Promise<void> {
    if (running.value || options.rows.length === 0) return;
    try {
      await ElMessageBox.confirm(options.message, options.title, { type: "warning" });
    } catch {
      return; // cancelled
    }
    running.value = true;
    try {
      const result = await runBatch(options.rows, options.action);
      if (result.failed.length === 0) {
        ElMessage.success(t("batch.allDone", { n: result.succeeded.length }));
      } else {
        const details = result.failed
          .slice(0, MAX_LISTED_FAILURES)
          .map(failure => {
            const reason =
              failure.error instanceof ApiError ? failure.error.displayMessage : t("batch.unknown");
            return `${options.label(failure.item)}: ${reason}`;
          })
          .join("; ");
        ElMessage({
          type: result.succeeded.length ? "warning" : "error",
          duration: 8000,
          showClose: true,
          message: t("batch.partial", {
            ok: result.succeeded.length,
            failed: result.failed.length,
            details
          })
        });
      }
    } finally {
      running.value = false;
      await options.after();
    }
  }

  return { running, run };
}
