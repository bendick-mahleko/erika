// German and English must offer the same texts (NF-13): fails if a key exists in only one file.
import { readFileSync } from "node:fs";

const load = (lang) => JSON.parse(readFileSync(new URL(`../messages/${lang}.json`, import.meta.url), "utf-8"));
const keys = (obj, prefix = "") =>
  Object.entries(obj).flatMap(([k, v]) => (v && typeof v === "object" ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`]));

const de = new Set(keys(load("de")));
const en = new Set(keys(load("en")));
const onlyDe = [...de].filter((k) => !en.has(k));
const onlyEn = [...en].filter((k) => !de.has(k));
if (onlyDe.length || onlyEn.length) {
  console.error(
    `Message keys differ.\nOnly in de.json: ${onlyDe.join(", ") || "–"}\nOnly in en.json: ${onlyEn.join(", ") || "–"}`,
  );
  process.exit(1);
}
console.log(`messages: ${de.size} keys in de and en`);
