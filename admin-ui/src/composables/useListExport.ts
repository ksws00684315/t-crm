import { ElMessage } from "element-plus";
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import type { PageResult } from "@/api/types";
import { collectPages, saveCsv, toCsv, type ExportColumn } from "@/utils/export-csv";
import { notifyError } from "@/utils/notify";

/**
 * "Export current results" for a list page. `fetchPage` must build its query from the applied
 * search snapshot (not the half-typed form) so the file matches what the table shows.
 */
export function useListExport<T>(options: {
  fileBase: () => string;
  columns: () => ExportColumn<T>[];
  fetchPage: (page: number, size: number) => Promise<PageResult<T>>;
}) {
  const { t } = useI18n();
  const exporting = ref(false);

  async function exportCsv(): Promise<void> {
    if (exporting.value) return;
    exporting.value = true;
    try {
      const result = await collectPages(options.fetchPage);
      saveCsv(options.fileBase(), toCsv(options.columns(), result.rows));
      if (result.truncated) {
        ElMessage.warning(t("export.truncated", { n: result.rows.length, total: result.total }));
      } else {
        ElMessage.success(t("export.done", { n: result.rows.length }));
      }
    } catch (error) {
      notifyError(error);
    } finally {
      exporting.value = false;
    }
  }

  return { exporting, exportCsv };
}
