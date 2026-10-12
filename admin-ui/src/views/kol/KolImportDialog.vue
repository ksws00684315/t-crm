<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { downloadKolTemplate, uploadKolImport } from "@/api/kol/kol";
import ImportDialog from "@/components/ImportDialog.vue";

defineOptions({ name: "KolImportDialog" });

defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "finished"): void;
}>();

const { t } = useI18n();
const title = computed(() => t("kol.importTitle"));
const limitHint = computed(() => t("kol.limitHint"));
</script>

<template>
  <ImportDialog
    :model-value="modelValue"
    :title="title"
    :limit-hint="limitHint"
    template-file-name="kol-import-template.xlsx"
    task-type="KOL_IMPORT"
    :download-template="downloadKolTemplate"
    :upload="uploadKolImport"
    @update:model-value="emit('update:modelValue', $event)"
    @finished="emit('finished')"
  />
</template>
