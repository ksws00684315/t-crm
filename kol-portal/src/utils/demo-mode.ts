/**
 * Single source of the demo-mode decision (#133 item 1): the dev server running `vite --mode
 * demo`. Every module that behaves differently in the demo imports this constant instead of
 * restating the expression (a condition change must not be repeated four times).
 */
export const isDemoMode = import.meta.env.DEV && import.meta.env.MODE === "demo";
