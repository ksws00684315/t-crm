<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  createSheetSource,
  editSheetSource,
  type SheetSourceRequest
} from "@/api/product/sheet-source";
import type { SheetSourceVO, ShopVO } from "@/api/types";
import { notifyError } from "@/utils/notify";

defineOptions({ name: "SheetSourceFormDialog" });

const { t } = useI18n();
const COLUMN = /^[A-Z]{1,3}$/;
const CURRENCIES = ["USD", "CNY", "EUR", "GBP"];

const props = defineProps<{
  modelValue: boolean;
  source: SheetSourceVO | null;
  shops: ShopVO[];
}>();
const emit = defineEmits<{
  (event: "update:modelValue", value: boolean): void;
  (event: "saved"): void;
}>();

const form = reactive({
  sourceName: "",
  sheet: "",
  sheetTitle: "",
  headerRow: 1,
  linkColumn: "",
  nameColumn: "",
  priceColumn: "",
  categoryColumn: "",
  imageColumn: "",
  defaultShopId: "",
  priceCurrency: "USD"
});
const saving = ref(false);
const error = ref("");

const title = computed(() =>
  props.source ? t("sheetSource.editTitle") : t("sheetSource.createTitle")
);

watch(
  () => props.modelValue,
  open => {
    if (!open) return;
    const s = props.source;
    error.value = "";
    form.sourceName = s?.sourceName ?? "";
    form.sheet = s ? `https://docs.google.com/spreadsheets/d/${s.spreadsheetId}` : "";
    form.sheetTitle = s?.sheetTitle ?? "";
    form.headerRow = s?.headerRow ?? 1;
    form.linkColumn = s?.linkColumn ?? "";
    form.nameColumn = s?.nameColumn ?? "";
    form.priceColumn = s?.priceColumn ?? "";
    form.categoryColumn = s?.categoryColumn ?? "";
    form.imageColumn = s?.imageColumn ?? "";
    form.defaultShopId = s?.defaultShopId ?? "";
    form.priceCurrency = s?.priceCurrency ?? "USD";
  }
);

function upper(value: string): string {
  return value.trim().toUpperCase();
}

/** Mirrors the backend rules; returns an error message or "" when the form is valid. */
function validate(request: SheetSourceRequest): string {
  if (!request.sourceName || !request.sheetTitle) return t("sheetSource.requiredFields");
  if (!request.sheet) return t("sheetSource.invalidSheet");
  const required = [request.linkColumn, request.nameColumn, request.priceColumn];
  const optional = [request.categoryColumn, request.imageColumn].filter(Boolean) as string[];
  if (![...required, ...optional].every(c => COLUMN.test(c))) {
    return t("sheetSource.invalidColumn");
  }
  if (new Set(required).size < 3) return t("sheetSource.columnsDuplicate");
  return "";
}

async function save() {
  const request: SheetSourceRequest = {
    sourceName: form.sourceName.trim(),
    sheet: form.sheet.trim(),
    sheetTitle: form.sheetTitle.trim(),
    headerRow: form.headerRow,
    linkColumn: upper(form.linkColumn),
    nameColumn: upper(form.nameColumn),
    priceColumn: upper(form.priceColumn),
    categoryColumn: upper(form.categoryColumn) || null,
    imageColumn: upper(form.imageColumn) || null,
    defaultShopId: form.defaultShopId || null,
    priceCurrency: form.priceCurrency
  };
  error.value = validate(request);
  if (error.value) return;
  saving.value = true;
  try {
    if (props.source) {
      await editSheetSource(props.source.id, request);
      ElMessage.success(t("sheetSource.saved"));
    } else {
      await createSheetSource(request);
      ElMessage.success(t("sheetSource.created"));
    }
    emit("update:modelValue", false);
    emit("saved");
  } catch (e) {
    notifyError(e);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="600px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-width="120px" @submit.prevent>
      <el-form-item :label="t('sheetSource.name')" required>
        <el-input v-model="form.sourceName" maxlength="64" data-test="source-name" />
      </el-form-item>
      <el-form-item :label="t('sheetSource.sheet')" required>
        <el-input
          v-model="form.sheet"
          maxlength="512"
          :placeholder="t('sheetSource.sheetPlaceholder')"
          data-test="source-sheet"
        />
      </el-form-item>
      <el-form-item :label="t('sheetSource.sheetTitle')" required>
        <el-input v-model="form.sheetTitle" maxlength="100" data-test="source-tab" />
      </el-form-item>
      <el-form-item :label="t('sheetSource.headerRow')" required>
        <el-input-number v-model="form.headerRow" :min="1" :max="100" />
        <span class="hint">{{ t("sheetSource.headerRowHint") }}</span>
      </el-form-item>
      <el-form-item :label="t('sheetSource.columns')" required>
        <div class="columns">
          <el-input
            v-model="form.linkColumn"
            maxlength="3"
            :placeholder="t('sheetSource.columnPlaceholder')"
            data-test="col-link"
          >
            <template #prepend>{{ t("sheetSource.linkColumn") }}</template>
          </el-input>
          <el-input v-model="form.nameColumn" maxlength="3" data-test="col-name">
            <template #prepend>{{ t("sheetSource.nameColumn") }}</template>
          </el-input>
          <el-input v-model="form.priceColumn" maxlength="3" data-test="col-price">
            <template #prepend>{{ t("sheetSource.priceColumn") }}</template>
          </el-input>
          <el-input
            v-model="form.categoryColumn"
            maxlength="3"
            :placeholder="t('sheetSource.optional')"
          >
            <template #prepend>{{ t("sheetSource.categoryColumn") }}</template>
          </el-input>
          <el-input
            v-model="form.imageColumn"
            maxlength="3"
            :placeholder="t('sheetSource.optional')"
          >
            <template #prepend>{{ t("sheetSource.imageColumn") }}</template>
          </el-input>
        </div>
      </el-form-item>
      <el-form-item :label="t('sheetSource.defaultShop')">
        <el-select v-model="form.defaultShopId" clearable data-test="default-shop">
          <el-option v-for="shop in shops" :key="shop.id" :label="shop.shopName" :value="shop.id" />
        </el-select>
        <span class="hint">{{ t("sheetSource.defaultShopHint") }}</span>
      </el-form-item>
      <el-form-item :label="t('sheetSource.currency')" required>
        <el-select v-model="form.priceCurrency" data-test="currency">
          <el-option v-for="code in CURRENCIES" :key="code" :label="code" :value="code" />
        </el-select>
      </el-form-item>
      <el-alert v-if="error" :title="error" type="error" :closable="false" data-test="form-error" />
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="saving" data-test="save" @click="save">
        {{ t("common.save") }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.columns {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 8px;
  width: 100%;
}

.hint {
  margin-left: 8px;
  color: var(--tcrm-color-text-secondary);
  font-size: 12px;
}
</style>
