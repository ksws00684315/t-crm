import { defineStore } from "pinia";
import { ref } from "vue";

/** One reauth dialog for every concurrent 401. The HTTP handler only opens it. */
export const useReauthStore = defineStore("reauth", () => {
  const visible = ref(false);

  function open(): void {
    visible.value = true;
  }

  function close(): void {
    visible.value = false;
  }

  return { visible, open, close };
});
