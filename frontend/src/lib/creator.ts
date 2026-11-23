import type {
  DraftProblem,
  InvoiceContent,
  InvoiceFormat,
  InvoiceSettings,
  VatRate,
  VatTreatment,
} from "./api/browser";

/** UN/ECE Rec 20 units offered in the forms (labels in Creator.unit). */
export const UNITS = [
  "C62",
  "HUR",
  "DAY",
  "MON",
  "LS",
  "KGM",
  "MTR",
  "MTK",
  "MTQ",
  "LTR",
  "KMT",
  "SET",
  "KWH",
] as const;
export const FORMATS: InvoiceFormat[] = ["ZUGFERD_EN16931", "XRECHNUNG_CII", "XRECHNUNG_UBL"];
export const TREATMENTS: VatTreatment[] = ["DOMESTIC", "REVERSE_CHARGE", "INTRA_EU"];
export const RATES: VatRate[] = ["STANDARD_19", "REDUCED_7", "ZERO"];

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const DECIMAL = /^-?\d{1,12}([.,]\d{1,4})?$/;

/** "55,5" or "55.5" → "55.5"; anything else unchanged (the server rejects it). */
export function normaliseDecimal(value: string): string {
  const v = value.trim();
  return DECIMAL.test(v) ? v.replace(",", ".") : v;
}

export function isXRechnung(format: InvoiceFormat | undefined): boolean {
  return format === "XRECHNUNG_CII" || format === "XRECHNUNG_UBL";
}

/**
 * The same checks as the back end (CR-01, `DraftChecks`), for instant feedback while typing.
 * The server repeats every check; its answer wins after each save.
 */
export function clientProblems(content: InvoiceContent, settings: InvoiceSettings | null): DraftProblem[] {
  const p: DraftProblem[] = [];
  const req = (field: string, value: string | undefined | null) => {
    if (!value || !value.trim()) p.push({ field, code: "REQUIRED" });
  };
  if (!content.format) p.push({ field: "format", code: "REQUIRED" });
  const b = content.buyer ?? {};
  req("buyer.name", b.name);
  req("buyer.street", b.street);
  req("buyer.postalCode", b.postalCode);
  req("buyer.city", b.city);
  if (!b.countryCode) p.push({ field: "buyer.countryCode", code: "REQUIRED" });
  else if (!/^[A-Z]{2}$/.test(b.countryCode)) p.push({ field: "buyer.countryCode", code: "INVALID" });
  if (b.email && !EMAIL.test(b.email.trim())) p.push({ field: "buyer.email", code: "INVALID" });
  if (isXRechnung(content.format)) {
    if (!content.buyerReference?.trim()) p.push({ field: "buyerReference", code: "REQUIRED_FOR_XRECHNUNG" });
    if (!b.email?.trim()) p.push({ field: "buyer.email", code: "REQUIRED_FOR_XRECHNUNG" });
  }
  const period = Boolean(content.periodStart || content.periodEnd);
  if (!content.deliveryDate && !period) p.push({ field: "deliveryDate", code: "REQUIRED" });
  if (period) {
    if (!content.periodStart) p.push({ field: "periodStart", code: "REQUIRED" });
    if (!content.periodEnd) p.push({ field: "periodEnd", code: "REQUIRED" });
    if (content.periodStart && content.periodEnd && content.periodEnd < content.periodStart) {
      p.push({ field: "periodEnd", code: "PERIOD_ORDER" });
    }
  }
  const treatment = content.vatTreatment ?? "DOMESTIC";
  const klein = settings?.kleinunternehmer ?? false;
  if (!klein && treatment !== "DOMESTIC" && !b.vatId?.trim()) p.push({ field: "buyer.vatId", code: "VAT_ID_REQUIRED" });
  const lines = content.lines ?? [];
  if (lines.length === 0) p.push({ field: "lines", code: "REQUIRED" });
  lines.forEach((line, i) => {
    const f = `lines[${i}]`;
    req(`${f}.name`, line.name);
    if (!line.quantity) p.push({ field: `${f}.quantity`, code: "REQUIRED" });
    else if (!DECIMAL.test(line.quantity) || Number(line.quantity.replace(",", ".")) <= 0) {
      p.push({ field: `${f}.quantity`, code: "INVALID" });
    }
    if (!line.unitCode) p.push({ field: `${f}.unitCode`, code: "REQUIRED" });
    if (!line.netPrice) p.push({ field: `${f}.netPrice`, code: "REQUIRED" });
    else if (!DECIMAL.test(line.netPrice) || Number(line.netPrice.replace(",", ".")) < 0) {
      p.push({ field: `${f}.netPrice`, code: "INVALID" });
    }
    if (!klein && treatment === "DOMESTIC" && !line.vatRate) p.push({ field: `${f}.vatRate`, code: "REQUIRED" });
  });
  return p;
}

/** Problems for one field path, for aria-invalid and the inline message. */
export function problemFor(problems: DraftProblem[], field: string): DraftProblem | undefined {
  return problems.find((p) => p.field === field);
}

/** "lines[2].quantity" → { line: 3, key: "quantity" }; other paths → { key: path }. */
export function splitField(field: string): { line?: number; key: string } {
  const m = /^lines\[(\d+)]\.(.+)$/.exec(field);
  return m ? { line: Number(m[1]) + 1, key: m[2] ?? field } : { key: field };
}
