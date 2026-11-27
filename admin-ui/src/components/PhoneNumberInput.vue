<!-- Mirrored in kol-portal/src/components/PhoneNumberInput.vue (task 161); keep both copies in sync. -->
<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  composeE164,
  countryName,
  defaultIso,
  filterCountries,
  findCountry,
  splitE164
} from "@/utils/dial-codes";

defineOptions({ name: "PhoneNumberInput", inheritAttrs: false });

const props = defineProps<{
  modelValue: string;
  inputId?: string;
  placeholder?: string;
  disabled?: boolean;
}>();

const emit = defineEmits<{ "update:modelValue": [value: string] }>();

const { t, locale } = useI18n();

/**
 * The form keeps a plain E.164 string as its model; the dial code segment and the national
 * digits are the editing view of that value, so switching the country or typing only re-sends
 * the composed number and never changes what the backend receives.
 */
const iso = ref(defaultIso(navigator.language));
const national = ref("");
const query = ref("");
const options = computed(() => filterCountries(query.value, locale.value));
const selectedName = computed(() => {
  const country = findCountry(iso.value);
  return country ? countryName(country, locale.value) : "";
});

function emitValue() {
  emit("update:modelValue", composeE164(iso.value, national.value));
}

watch(
  () => props.modelValue,
  value => {
    if (value === composeE164(iso.value, national.value)) return;
    if (!value) {
      iso.value = defaultIso(navigator.language);
      national.value = "";
      return;
    }
    const split = splitE164(value);
    iso.value = split?.iso ?? "";
    national.value = split?.national ?? value.replace(/\D/g, "");
  },
  { immediate: true }
);

function onCountryChange() {
  query.value = "";
  emitValue();
}

function onFilter(text: string) {
  query.value = text;
}

/** Separators are dropped while typing; a pasted "+49 171…" switches the country as well. */
function onInput(raw: string) {
  if (raw.trim().startsWith("+")) {
    const split = splitE164(raw);
    if (split) {
      iso.value = split.iso;
      national.value = split.national;
      emitValue();
      return;
    }
  }
  national.value = raw.replace(/\D/g, "");
  emitValue();
}
</script>

<template>
  <el-input
    :id="inputId"
    v-bind="$attrs"
    class="phone-input"
    :model-value="national"
    inputmode="tel"
    autocomplete="tel"
    :disabled="disabled"
    :placeholder="placeholder"
    @input="onInput"
  >
    <template #prepend>
      <el-select
        v-model="iso"
        class="phone-input__code"
        filterable
        :filter-method="onFilter"
        :disabled="disabled"
        :aria-label="selectedName || t('phone.countryPlaceholder')"
        @change="onCountryChange"
      >
        <template #label="{ value }">+{{ findCountry(value as string)?.dial ?? "" }}</template>
        <el-option
          v-for="country in options"
          :key="country.iso"
          :label="countryName(country, locale)"
          :value="country.iso"
        />
      </el-select>
    </template>
  </el-input>
</template>

<style scoped>
.phone-input {
  width: 100%;
}

/*
 * The dial code is its own segment of the one-pill field rather than text inside the number
 * box: the resting border belongs to the input group, so the nested select drops its own and
 * only shows a focus ring while it is being changed. Element Plus stretches a select inside a
 * prepend with negative margins meant for the default 20px padding, which would clip the caret
 * and the left edge here, so the segment owns its box instead.
 */
.phone-input :deep(.el-input-group__prepend) {
  padding: 0;
}

.phone-input :deep(.el-input-group__prepend .el-select) {
  display: inline-flex;
  flex: none;
  width: 96px;
  margin: 0;
}

.phone-input__code :deep(.el-select__wrapper) {
  background: transparent;
  box-shadow: none;
  padding: 0 var(--tcrm-space-2);
}

.phone-input__code :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}
</style>
