import { getFormatter, getTranslations } from "next-intl/server";
import type { Health, HealthStatus } from "@/lib/api/client";

const statusStyle: Record<HealthStatus, string> = {
  UP: "bg-green-100 text-green-900 ring-green-700",
  DEGRADED: "bg-amber-100 text-amber-900 ring-amber-700",
  DOWN: "bg-red-100 text-red-900 ring-red-700",
};

const componentKeys = ["database", "objectStorage", "worker"] as const;

function StatusBadge({ status, label }: { status: HealthStatus; label: string }) {
  // Text label as well as colour, so the status doesn't rely on colour alone (WCAG 1.4.1).
  return (
    <span className={`inline-flex rounded-full px-3 py-1 text-sm font-medium ring-1 ${statusStyle[status]}`}>
      {label}
    </span>
  );
}

export async function HealthPanel({ health }: { health: Health | null }) {
  const t = await getTranslations("Health");
  const format = await getFormatter();

  return (
    <section aria-labelledby="health-heading" className="rounded-lg border border-slate-200 p-6 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="health-heading" className="text-lg font-semibold">
          {t("heading")}
        </h2>
        {health && <StatusBadge status={health.status} label={t(`status.${health.status}`)} />}
      </div>

      {health ? (
        <>
          <ul className="mt-4 divide-y divide-slate-100" data-testid="health-components">
            {componentKeys.map((key) => {
              const component = health.components[key];
              return (
                <li key={key} className="flex flex-wrap items-center justify-between gap-2 py-3">
                  <span>{t(`component.${key}`)}</span>
                  <span className="flex items-center gap-3">
                    {component.detail && <span className="text-sm text-slate-600">{component.detail}</span>}
                    <StatusBadge status={component.status} label={t(`status.${component.status}`)} />
                  </span>
                </li>
              );
            })}
          </ul>
          <p className="mt-4 text-sm text-slate-600">
            {t("version", { version: health.version })} ·{" "}
            {t("checkedAt", {
              checkedAt: format.dateTime(new Date(health.checkedAt), { dateStyle: "medium", timeStyle: "medium" }),
            })}
          </p>
        </>
      ) : (
        <p role="alert" className="mt-4 text-red-800">
          {t("unreachable")}
        </p>
      )}
    </section>
  );
}
