"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useFormatter, useTranslations } from "next-intl";
import { browserApi, type DocumentSummary, type IntakeItem, type Upload } from "@/lib/api/browser";

const MAX_BYTES = 20 * 1024 * 1024;
const FINISHED = new Set(["DONE", "REJECTED", "FAILED"]);

type UploadLine =
  | { key: string; name: string; state: "uploading" | "processing" | "failed" | "tooLarge" }
  | { key: string; name: string; state: "finished"; upload: Upload };

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * Upload (IN-02) and the list of received documents. Files go straight to object storage
 * with a pre-signed URL; the list refreshes while the worker is still checking documents.
 */
export function InboxClient({
  initial,
  nextCursor,
  canUpload,
}: {
  initial: DocumentSummary[];
  nextCursor?: string;
  canUpload: boolean;
}) {
  const t = useTranslations("Upload");
  const [documents, setDocuments] = useState(initial);
  const [cursor, setCursor] = useState(nextCursor);
  const [lines, setLines] = useState<UploadLine[]>([]);
  const [dragging, setDragging] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    const { data } = await browserApi.GET("/documents", { params: { query: { limit: 50 } } });
    if (data) {
      setDocuments(data.items);
      setCursor(data.nextCursor);
    }
  }, []);

  // While documents are still being checked, look again every few seconds.
  const pending = documents.some((d) => d.processing === "PENDING");
  useEffect(() => {
    if (!pending) return;
    const timer = setInterval(refresh, 3000);
    return () => clearInterval(timer);
  }, [pending, refresh]);

  const setLine = (line: UploadLine) => setLines((all) => all.map((l) => (l.key === line.key ? line : l)));

  async function uploadOne(file: File, key: string) {
    const name = file.name;
    try {
      const { data: started } = await browserApi.POST("/uploads", {
        body: { fileName: name, size: file.size, contentType: file.type || "application/octet-stream" },
      });
      if (!started?.uploadUrl) throw new Error("start");
      const put = await fetch(started.uploadUrl, { method: "PUT", headers: started.uploadHeaders, body: file });
      if (!put.ok) throw new Error("put");
      const { data: completed } = await browserApi.POST("/uploads/{uploadId}/complete", {
        params: { path: { uploadId: started.id } },
      });
      if (!completed) throw new Error("complete");
      setLine({ key, name, state: "processing" });
      for (let i = 0; i < 120; i++) {
        const { data } = await browserApi.GET("/uploads/{uploadId}", { params: { path: { uploadId: started.id } } });
        if (data && FINISHED.has(data.status)) {
          setLine({ key, name, state: "finished", upload: data });
          return;
        }
        await sleep(1000);
      }
      setLine({ key, name, state: "failed" });
    } catch {
      setLine({ key, name, state: "failed" });
    }
  }

  async function uploadAll(files: FileList | File[]) {
    const list = Array.from(files);
    const added: UploadLine[] = list.map((f, i) => ({
      key: `${Date.now()}-${i}-${f.name}`,
      name: f.name,
      state: f.size > MAX_BYTES || f.size === 0 ? "tooLarge" : "uploading",
    }));
    setLines((all) => [...added, ...all]);
    await Promise.all(list.map((f, i) => (added[i]!.state === "uploading" ? uploadOne(f, added[i]!.key) : null)));
    await refresh();
  }

  return (
    <div className="space-y-8">
      {canUpload && (
        <section aria-labelledby="upload-heading" className="space-y-3">
          <h2 id="upload-heading" className="text-lg font-semibold">
            {t("heading")}
          </h2>
          <div
            onDragOver={(e) => {
              e.preventDefault();
              setDragging(true);
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={(e) => {
              e.preventDefault();
              setDragging(false);
              if (e.dataTransfer.files.length > 0) void uploadAll(e.dataTransfer.files);
            }}
            className={`rounded-lg border-2 border-dashed p-6 text-center ${
              dragging ? "border-brand-600 bg-brand-50" : "border-slate-300"
            }`}
          >
            <p>
              {t("drop")}{" "}
              <button
                type="button"
                onClick={() => input.current?.click()}
                className="rounded bg-brand-700 px-3 py-1 font-medium text-white hover:bg-brand-900"
              >
                {t("choose")}
              </button>
            </p>
            <input
              ref={input}
              type="file"
              multiple
              accept=".xml,.pdf,.zip,application/xml,text/xml,application/pdf,application/zip"
              className="sr-only"
              aria-label={t("choose")}
              data-testid="upload-input"
              onChange={(e) => {
                if (e.target.files && e.target.files.length > 0) void uploadAll(e.target.files);
                e.target.value = "";
              }}
            />
            <p className="mt-2 text-sm text-slate-600">{t("hint")}</p>
          </div>
          <UploadResults lines={lines} />
        </section>
      )}
      <DocumentListView documents={documents} cursor={cursor} onMore={setDocuments} onCursor={setCursor} />
    </div>
  );
}

function UploadResults({ lines }: { lines: UploadLine[] }) {
  const t = useTranslations("Upload");
  const tr = useTranslations("IntakeReason");
  if (lines.length === 0) return <div role="status" aria-live="polite" />;

  const itemText = (item: IntakeItem) => {
    const reason = item.reason ? tr(item.reason) : "";
    if (item.outcome === "IMPORTED") return t("itemImported", { name: item.fileName });
    if (item.outcome === "ALREADY_IN_ARCHIVE") return t("itemDuplicate", { name: item.fileName });
    return t("itemRejected", { name: item.fileName, reason });
  };

  return (
    <div role="status" aria-live="polite" aria-label={t("resultsHeading")}>
      <ul className="space-y-1" data-testid="upload-results">
        {lines.map((line) => {
          if (line.state !== "finished") {
            const failed = line.state === "failed" || line.state === "tooLarge";
            return (
              <li key={line.key} className={failed ? "text-red-800" : "text-slate-700"}>
                {t(line.state, { name: line.name })}
              </li>
            );
          }
          const upload = line.upload;
          if (upload.status !== "DONE" || upload.items.length === 0) {
            const reason = upload.reason ? tr(upload.reason) : t("failed", { name: line.name });
            return (
              <li key={line.key} className="text-red-800">
                {t("rejected", { name: line.name, reason })}
              </li>
            );
          }
          return upload.items.map((item, i) => (
            <li
              key={`${line.key}-${i}`}
              className={
                item.outcome === "IMPORTED"
                  ? "text-green-800"
                  : item.outcome === "ALREADY_IN_ARCHIVE"
                    ? "text-amber-800"
                    : "text-red-800"
              }
            >
              {itemText(item)}{" "}
              {item.documentId && (
                <Link href={`/documents/${item.documentId}`} className="underline">
                  {t("openDocument")}
                </Link>
              )}
            </li>
          ));
        })}
      </ul>
    </div>
  );
}

function DocumentListView({
  documents,
  cursor,
  onMore,
  onCursor,
}: {
  documents: DocumentSummary[];
  cursor?: string;
  onMore: (docs: DocumentSummary[]) => void;
  onCursor: (cursor?: string) => void;
}) {
  const t = useTranslations("Inbox");
  const td = useTranslations("Document");
  const format = useFormatter();
  const processing = documents.filter((d) => d.processing === "PENDING").length;

  async function more() {
    const { data } = await browserApi.GET("/documents", { params: { query: { limit: 50, cursor } } });
    if (data) {
      onMore([...documents, ...data.items]);
      onCursor(data.nextCursor);
    }
  }

  return (
    <section aria-labelledby="documents-heading" className="space-y-3">
      <h2 id="documents-heading" className="text-lg font-semibold">
        {t("listHeading")}
      </h2>
      <p role="status" className="text-sm text-slate-700">
        {processing > 0 ? t("processingNote", { count: processing }) : ""}
      </p>
      {documents.length === 0 ? (
        <p className="text-slate-700">{t("empty")}</p>
      ) : (
        <ul className="divide-y divide-slate-200 rounded-lg border border-slate-200" data-testid="document-list">
          {documents.map((d) => (
            <li key={d.id} className="flex flex-wrap items-start gap-x-6 gap-y-1 p-4">
              <div className="min-w-0 flex-1 basis-60">
                <Link href={`/documents/${d.id}`} className="font-medium break-all text-brand-700 underline">
                  {d.sellerName ?? d.fileName ?? td("format.UNKNOWN")}
                </Link>
                <p className="text-sm text-slate-600">
                  {format.dateTime(new Date(d.receivedAt), { dateStyle: "medium", timeStyle: "short" })} ·{" "}
                  {t(`via.${d.receivedVia}`)}
                  {d.invoiceNumber ? ` · ${t("column.number")} ${d.invoiceNumber}` : ""}
                  {d.sellerName && d.fileName ? ` · ${d.fileName}` : ""}
                </p>
              </div>
              {d.grossAmount && (
                <p className="font-medium tabular-nums">
                  {format.number(Number(d.grossAmount), { style: "currency", currency: d.currency ?? "EUR" })}
                </p>
              )}
              <div className="flex flex-wrap gap-2">
                <ResultBadge doc={d} />
                {d.duplicateOf && (
                  <span className="rounded bg-amber-100 px-2 py-0.5 text-sm text-amber-900" title={td("duplicateHint")}>
                    {td("duplicate")}
                  </span>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {cursor && (
        <button type="button" onClick={more} className="rounded border border-slate-400 px-3 py-1">
          {t("more")}
        </button>
      )}
    </section>
  );
}

export function ResultBadge({
  doc,
}: {
  doc: Pick<DocumentSummary, "processing" | "notEInvoiceReason" | "validationLevel">;
}) {
  const td = useTranslations("Document");
  if (doc.processing === "PENDING")
    return <span className="rounded bg-slate-100 px-2 py-0.5 text-sm">{td("processing")}</span>;
  if (doc.processing === "FAILED")
    return <span className="rounded bg-red-100 px-2 py-0.5 text-sm text-red-900">{td("processingFailed")}</span>;
  if (doc.notEInvoiceReason)
    return <span className="rounded bg-slate-200 px-2 py-0.5 text-sm text-slate-900">{td("notEInvoice")}</span>;
  const level = doc.validationLevel ?? "ERROR";
  const style =
    level === "VALID"
      ? "bg-green-100 text-green-900"
      : level === "WARNING"
        ? "bg-amber-100 text-amber-900"
        : "bg-red-100 text-red-900";
  return <span className={`rounded px-2 py-0.5 text-sm ${style}`}>{td(`level.${level}`)}</span>;
}
