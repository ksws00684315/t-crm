import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import type { RouteRecordRaw } from "vue-router";
import { parse } from "vue/compiler-sfc";
import { routes } from "@/router/routes";

/**
 * Guard against pages that break the page transition by rendering a fragment root.
 *
 * `src/layout/index.vue` mounts the routed page inside `<Transition name="tcrm-page" mode="out-in">`.
 * BaseTransition can only animate a single element, so a page whose template has several root nodes
 * (a Fragment) breaks the out-in state machine: Vue warns "Component inside <Transition> renders
 * non-element root node that cannot be animated", the leave hook never reaches `afterLeave`, the
 * transition stays in `isLeaving` and every page mounted afterwards renders only its placeholder
 * comment. The content area then stays blank until the whole browser tab is reloaded.
 *
 * A root `<div v-if="…">…</div>` / `<div v-else>…</div>` pair is fine — the compiler folds the chain
 * into one conditional element. What is not fine is a root `<template>` with several children, or a
 * root text node: both compile to a Fragment.
 */

// Vue AST node types (NodeTypes of @vue/compiler-core), spelled out to avoid an extra dependency.
const ELEMENT = 1;
const TEXT = 2;
const COMMENT = 3;
const DIRECTIVE = 7;

interface AxNode {
  type: number;
  tag?: string;
  content?: unknown;
  props?: { type: number; name?: string }[];
  children?: AxNode[];
}

function walk(list: readonly RouteRecordRaw[]): RouteRecordRaw[] {
  return list.flatMap(route => [route, ...(route.children ? walk(route.children) : [])]);
}

/** Pages that the layout mounts inside the page Transition (same population as component-names). */
const pages = walk(routes).filter(
  route => typeof route.meta?.componentName === "string" && typeof route.component === "function"
);

/**
 * Route component factories keep the module specifier; Vite only wraps the loader, e.g.
 * `() => __vite_ssr_dynamic_import__("/src/views/home/index.vue")`.
 */
function modulePath(route: RouteRecordRaw): string {
  const match = /["'`]([^"'`]+\.vue)["'`]/.exec(String(route.component));
  if (!match) throw new Error(`cannot resolve the module of route ${String(route.path)}`);
  return match[1].replace(/^@\//, "").replace(/^\/?src\//, "");
}

const specDir = dirname(fileURLToPath(import.meta.url));

/** Reads the SFC source; `new URL(…, import.meta.url)` is rewritten by Vite's asset plugin. */
function templateSource(route: RouteRecordRaw): string {
  return readFileSync(resolve(specDir, "../..", modulePath(route)), "utf8");
}

/** Root children without the comments and whitespace text the compiler drops. */
function elementChildren(nodes: AxNode[]): AxNode[] {
  return nodes.filter(node => {
    if (node.type === COMMENT) return false;
    if (node.type === TEXT) return Boolean(String(node.content ?? "").trim());
    return true;
  });
}

function directiveNames(node: AxNode): Set<string> {
  return new Set(
    (node.props ?? [])
      .filter(prop => prop.type === DIRECTIVE && typeof prop.name === "string")
      .map(prop => String(prop.name))
  );
}

/** Sibling branches of one `v-if` / `v-else-if` / `v-else` chain, in template order. */
function rootBranches(nodes: AxNode[]): AxNode[][] {
  const branches: AxNode[][] = [];
  for (const node of nodes) {
    const names = node.type === ELEMENT ? directiveNames(node) : new Set<string>();
    const continuesChain = names.has("else-if") || names.has("else");
    if (continuesChain && branches.length > 0) branches[branches.length - 1].push(node);
    else branches.push([node]);
  }
  return branches;
}

/** Whether a root node compiles to exactly one element instead of a fragment. */
function rendersSingleElement(node: AxNode): boolean {
  if (node.type !== ELEMENT) return false;
  // `<slot>` and `<template>` roots forward their children, so they are only safe while they hold one.
  if (node.tag === "slot") return false;
  if (node.tag === "template") {
    const children = elementChildren(node.children ?? []);
    return children.length === 1 && rendersSingleElement(children[0]);
  }
  return true;
}

describe("page components mounted in the layout Transition", () => {
  it("covers every cacheable page", () => {
    expect(pages.length).toBeGreaterThan(20);
  });

  for (const route of pages) {
    const label = `${String(route.meta?.componentName)} (${String(route.path)})`;

    it(`${label} renders a single element root`, () => {
      const { descriptor } = parse(templateSource(route), { filename: modulePath(route) });
      expect(descriptor.template, `${label} must have a <template>`).toBeTruthy();
      const ast = descriptor.template?.ast as unknown as AxNode | undefined;
      const branches = rootBranches(elementChildren(ast?.children ?? []));

      expect(branches.length, `${label} has ${branches.length} root nodes`).toBe(1);
      for (const branch of branches[0]) {
        expect(
          rendersSingleElement(branch),
          `${label} renders a fragment root: wrap the template in one element`
        ).toBe(true);
      }
    });
  }
});
