import type { WorkOrderStatus } from "@/lib/api/browser";

/** All statuses, in workflow order (MARA M-OF-02). */
export const STATUSES: WorkOrderStatus[] = ["PLANNED", "IN_PROGRESS", "DONE", "SIGNED", "INVOICED", "CANCELLED"];

/** Statuses a user may set; SIGNED and INVOICED are set by MARA itself. */
export const USER_STATUSES: WorkOrderStatus[] = ["PLANNED", "IN_PROGRESS", "DONE", "CANCELLED"];

export const LOCKED: WorkOrderStatus[] = ["SIGNED", "INVOICED"];

/** `YYYY-MM-DD` of a date in the browser's time zone. */
export function isoDay(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** `HH:MM` of a date in the browser's time zone. */
export function hhmm(date: Date): string {
  return date.toTimeString().slice(0, 5);
}

/** Local midnight of a `YYYY-MM-DD` day, and of the next day: the calendar's range. */
export function dayRange(day: string): { from: Date; to: Date } {
  const [y, m, d] = day.split("-").map(Number) as [number, number, number];
  return { from: new Date(y, m - 1, d), to: new Date(y, m - 1, d + 1) };
}

/** A local date and time from form fields as an ISO timestamp, or undefined if either is empty. */
export function toInstant(day: string, time: string): string | undefined {
  if (!day || !time) return undefined;
  return new Date(`${day}T${time}`).toISOString();
}

export function statusClass(status: WorkOrderStatus): string {
  switch (status) {
    case "IN_PROGRESS":
      return "bg-amber-100 text-amber-900";
    case "DONE":
    case "SIGNED":
      return "bg-green-100 text-green-900";
    case "INVOICED":
      return "bg-slate-200 text-slate-800";
    case "CANCELLED":
      return "bg-red-50 text-red-900 line-through";
    default:
      return "bg-blue-50 text-blue-900";
  }
}
