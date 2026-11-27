import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus, { ElSelect } from "element-plus";
import { afterEach, describe, expect, it } from "vitest";
import { i18n } from "@/locales";
import { countryName, findCountry } from "@/utils/dial-codes";
import PhoneNumberInput from "../PhoneNumberInput.vue";

const wrappers: VueWrapper[] = [];

async function render(modelValue = "") {
  const wrapper = mount(PhoneNumberInput, {
    props: { modelValue, inputId: "phone" },
    global: { plugins: [ElementPlus, i18n] }
  });
  wrappers.push(wrapper);
  await flushPromises();
  return wrapper;
}

function numberInput(wrapper: VueWrapper) {
  return wrapper.get("#phone");
}

function value(wrapper: VueWrapper) {
  return (numberInput(wrapper).element as HTMLInputElement).value;
}

function lastEmitted(wrapper: VueWrapper) {
  return wrapper.emitted("update:modelValue")?.at(-1)?.[0];
}

async function chooseCountry(wrapper: VueWrapper, iso: string) {
  wrapper.findComponent(ElSelect).vm.$emit("update:modelValue", iso);
  await flushPromises();
}

afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()));

describe("phone number input", () => {
  it("shows the stored E.164 value as dial code segment plus national digits", async () => {
    const wrapper = await render("+491716422826");
    expect(wrapper.get(".phone-input__code").text()).toBe("+49");
    expect(value(wrapper)).toBe("1716422826");
  });

  it("names the selected country for assistive technology while the trigger shows the code", async () => {
    const wrapper = await render("+491716422826");
    const name = countryName(findCountry("DE")!, i18n.global.locale.value as string);
    expect(wrapper.get(".phone-input__code input").attributes("aria-label")).toBe(name);
    expect(wrapper.get(".phone-input__code").text()).not.toContain(name);
  });

  it("composes E.164 while typing and drops separators", async () => {
    const wrapper = await render();
    await chooseCountry(wrapper, "DE");
    await numberInput(wrapper).setValue("171 642-2826");
    expect(lastEmitted(wrapper)).toBe("+491716422826");
    expect(value(wrapper)).toBe("1716422826");
  });

  it("switches the country when a full international number is pasted", async () => {
    const wrapper = await render();
    await numberInput(wrapper).setValue("+49 171 6422826");
    expect(lastEmitted(wrapper)).toBe("+491716422826");
    expect(wrapper.get(".phone-input__code").text()).toBe("+49");
    expect(value(wrapper)).toBe("1716422826");
  });

  it("emits an empty value when the number is cleared", async () => {
    const wrapper = await render("+491716422826");
    await numberInput(wrapper).setValue("");
    expect(lastEmitted(wrapper)).toBe("");
  });
});
