<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from "element-plus";
import { supplementOrderRate } from "@/api/order/order";
import { percentToRate } from "@/utils/rate";
import { notifyError } from "@/utils/notify";
const props = defineProps<{
  modelValue: boolean;
  orderId: string;
  orderNo: string;
  itemId: string;
  itemName: string;
}>();
const emit = defineEmits<{ (e: "update:modelValue", value: boolean): void; (e: "saved"): void }>();
const { t } = useI18n();
const formRef = ref<FormInstance>();
const saving = ref(false);
const form = reactive({ percent: "", reason: "" });
const rules = computed<FormRules>(() => ({
  percent: [
    {
      validator: (_rule, value, callback) =>
        callback(percentToRate(value) === null ? new Error(t("validation.percent")) : undefined),
      trigger: "blur"
    }
  ],
  reason: [
    {
      required: true,
      whitespace: true,
      message: t("order.rateSupplement.reasonRequired"),
      trigger: "blur"
    }
  ]
}));
watch(
  () => props.modelValue,
  open => {
    if (open) {
      form.percent = "";
      form.reason = "";
      formRef.value?.clearValidate();
    }
  }
);
async function save() {
  if (saving.value) return;
  saving.value = true;
  try {
    if (!(await formRef.value?.validate().catch(() => false))) return;
    const rate = percentToRate(form.percent);
    if (rate === null) return;
    try {
      await ElMessageBox.confirm(
        t("order.rateSupplement.confirm"),
        t("order.rateSupplement.title"),
        { type: "warning" }
      );
    } catch {
      return;
    }
    await supplementOrderRate(props.orderId, {
      orderItemId: props.itemId,
      rate,
      reason: form.reason.trim()
    });
    ElMessage.success(t("order.rateSupplement.saved"));
    emit("saved");
    emit("update:modelValue", false);
  } catch (error) {
    notifyError(error);
  } finally {
    saving.value = false;
  }
}
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    :title="t('order.rateSupplement.title')"
    width="min(640px, calc(100vw - 24px))"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert type="info" :closable="false" :title="t('order.rateSupplement.note')" />
    <p>{{ orderNo }} · {{ itemName }} · {{ itemId }}</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <el-form-item :label="t('order.chain.summaryRate')" prop="percent"
        ><el-input v-model="form.percent" data-test="rate-percent"
          ><template #append>%</template></el-input
        ></el-form-item
      >
      <el-form-item :label="t('order.rateSupplement.reason')" prop="reason"
        ><el-input
          v-model="form.reason"
          type="textarea"
          maxlength="255"
          show-word-limit
          data-test="rate-reason"
      /></el-form-item>
    </el-form>
    <template #footer
      ><el-button :disabled="saving" @click="emit('update:modelValue', false)">{{
        t("common.cancel")
      }}</el-button
      ><el-button type="primary" :loading="saving" data-test="rate-save" @click="save">{{
        t("common.save")
      }}</el-button></template
    >
  </el-dialog>
</template>
