import { ref, watch, type Ref } from "vue";

/** Keep short detail fields in two columns only while their actual container is readable. */
export function useDescriptionColumns(container: Ref<HTMLElement | null>, maxColumns = 2) {
  const columns = ref(1);

  watch(
    container,
    (element, _previous, onCleanup) => {
      if (!element) return;
      const update = (width: number) => {
        columns.value = width >= 1100 && maxColumns === 4 ? 4 : width >= 560 ? 2 : 1;
      };
      update(element.clientWidth);
      if (typeof ResizeObserver !== "undefined") {
        const observer = new ResizeObserver(entries => {
          const entry = entries[0];
          if (entry) update(entry.contentRect.width);
        });
        observer.observe(element);
        onCleanup(() => observer.disconnect());
      } else {
        const onResize = () => update(element.clientWidth);
        window.addEventListener("resize", onResize);
        onCleanup(() => window.removeEventListener("resize", onResize));
      }
    },
    { flush: "post" }
  );

  return columns;
}
