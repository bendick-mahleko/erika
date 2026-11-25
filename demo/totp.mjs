// Prints the current 2FA codes for the demo accounts created by demo/seed-demo.mjs.
//
//   node demo/totp.mjs
import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";

function totp(secret, at = Date.now()) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of secret.replace(/=+$/, "")) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 30000)));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  return ((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, "0");
}

let account;
try {
  account = JSON.parse(readFileSync(new URL("./demo-account.local.json", import.meta.url), "utf-8"));
} catch {
  console.error("demo/demo-account.local.json not found: run node demo/seed-demo.mjs first.");
  process.exit(1);
}
const secondsLeft = 30 - (Math.floor(Date.now() / 1000) % 30);
for (const who of [account.owner, account.advisor].filter(Boolean)) {
  console.log(`${totp(who.totpSecret)}  ${who.email}`);
}
console.log(`(valid for another ${secondsLeft} s)`);
