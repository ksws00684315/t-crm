import { defineStore } from "pinia";
import { ref } from "vue";

/**
 * Open state for the header "search menu" control. The dialog itself is mounted with the menu
 * search step; the frame only needs a place to request it.
 */
export const useMenuSearchStore = defineStore("menuSearch", () => {
  const visible = ref(false);

  function open(): void {
    visible.value = true;
  }

  function close(): void {
    visible.value = false;
  }

  return { visible, open, close };
});
