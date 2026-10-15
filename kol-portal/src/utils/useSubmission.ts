import { computed, ref, shallowRef } from "vue";
import { translate } from "@/locales";
import { ApiError } from "@/utils/http/error";

/** Keep feedback as keys/errors so an in-place language change also updates existing feedback. */
export function useSubmission() {
  const busy = ref(false);
  const validation = ref("");
  const failure = shallowRef<ApiError | null>(null);
  const errorText = computed(() =>
    validation.value ? translate(validation.value) : (failure.value?.displayMessage ?? "")
  );

  async function submit(action: () => Promise<void>) {
    if (busy.value) return;
    validation.value = "";
    failure.value = null;
    busy.value = true;
    try {
      await action();
    } catch (error) {
      if (error instanceof ApiError) {
        if (!error.handled) failure.value = error;
      } else {
        validation.value = "errors.generic";
      }
    } finally {
      busy.value = false;
    }
  }
  return { busy, validation, failure, errorText, submit };
}
