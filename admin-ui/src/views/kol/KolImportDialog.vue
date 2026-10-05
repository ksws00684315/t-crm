<script setup lang="ts">
import { downloadKolTemplate, uploadKolImport } from "@/api/kol/kol";
import ImportDialog from "@/components/ImportDialog.vue";

defineOptions({ name: "KolImportDialog" });

defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "finished"): void;
}>();
</script>

<template>
  <ImportDialog
    :model-value="modelValue"
    title="批量导入达人"
    limit-hint="单个文件不超过 20MB、2000 行"
    template-file-name="kol-import-template.xlsx"
    task-type="KOL_IMPORT"
    :download-template="downloadKolTemplate"
    :upload="uploadKolImport"
    @update:model-value="emit('update:modelValue', $event)"
    @finished="emit('finished')"
  />
</template>
