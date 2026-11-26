import { createHash } from "node:crypto";

/*
 * The canonical report (mara-rules/vectors/report/canonical.json) for the end-to-end tests,
 * where the test plays the phone. The app (Dart) and the server (Kotlin) have their own
 * implementations; the test checks this one against the shared vectors before using it.
 */

const DECIMALS = new Set(["quantity", "length", "width", "height", "count", "result"]);
const INSTANTS = new Set(["start", "end", "signedAt"]);

type Json = string | number | boolean | null | Json[] | { [key: string]: Json };

function trim(s: string): string {
  return s.replace(/^[ \t\n\r]+|[ \t\n\r]+$/g, "");
}

function decimal(s: string): string {
  let t = s;
  if (t.includes(".")) t = t.replace(/0+$/, "").replace(/\.$/, "");
  return /^-?0*$/.test(t) || t === "-0" ? "0" : t;
}

function instant(s: string): string {
  return new Date(s).toISOString().replace(/\.\d{3}Z$/, "Z");
}

function normalize(value: Json | undefined, key?: string): Json | undefined {
  if (value === null || value === undefined) return undefined;
  if (Array.isArray(value)) {
    const items = value.map((v) => normalize(v)).filter((v): v is Json => v !== undefined);
    const maps = items.filter(
      (i): i is { [k: string]: Json } => typeof i === "object" && i !== null && !Array.isArray(i),
    );
    if (maps.length === 0 || maps.length !== items.length) return items;
    const sortKey = "id" in maps[0]! ? "id" : "key" in maps[0]! ? "key" : null;
    if (!sortKey) return items;
    // By UTF-16 code units, as Kotlin and Dart compare strings (not by locale).
    return [...maps].sort((a, b) => {
      const x = String(a[sortKey] ?? "");
      const y = String(b[sortKey] ?? "");
      return x < y ? -1 : x > y ? 1 : 0;
    });
  }
  if (typeof value === "object") {
    const out: { [k: string]: Json } = {};
    for (const [k, v] of Object.entries(value)) {
      const n = normalize(v, k);
      if (n !== undefined) out[k] = n;
    }
    return out;
  }
  if (typeof value === "boolean" || typeof value === "number") return value;
  const s = trim(value);
  if (s === "") return undefined;
  if (key && DECIMALS.has(key)) return decimal(s);
  if (key && INSTANTS.has(key)) return instant(s);
  return s;
}

function write(value: Json): string {
  if (Array.isArray(value)) return `[${value.map(write).join(",")}]`;
  if (value !== null && typeof value === "object") {
    const keys = Object.keys(value).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
    return `{${keys.map((k) => `${JSON.stringify(k)}:${write(value[k]!)}`).join(",")}}`;
  }
  // JSON.stringify escapes exactly ", \ and control characters (\b \f \n \r \t, else \u00xx).
  return JSON.stringify(value);
}

export function canonical(raw: Json): string {
  return write(normalize(raw)!);
}

export function canonicalHash(raw: Json): string {
  return createHash("sha256").update(canonical(raw), "utf8").digest("hex");
}
