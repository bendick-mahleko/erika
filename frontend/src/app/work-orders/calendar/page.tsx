import type { Metadata } from "next";
import { notFound, redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { fetchSession, fetchStaff } from "@/lib/api/client";
import { WorkOrdersNav } from "../WorkOrdersNav";
import { DayCalendar } from "./DayCalendar";

export async function generateMetadata(): Promise<Metadata> {
  const t = await getTranslations("WorkOrders");
  return { title: `${t("calendarTitle")} – ERIKA` };
}

export const dynamic = "force-dynamic";

/** Today as YYYY-MM-DD in German time: ERIKA's users work in Germany, the server may not. */
function todayInGermany(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Berlin" }).format(new Date());
}

/** Day view by person (MARA M-OF-03). */
export default async function CalendarPage({ searchParams }: { searchParams: Promise<{ day?: string }> }) {
  const session = await fetchSession();
  if (session.state !== "AUTHENTICATED") redirect("/login");
  if (!session.features?.mara) notFound();
  const { day } = await searchParams;
  const staff = await fetchStaff();
  const t = await getTranslations("WorkOrders");
  const canEdit = session.role === "OWNER" || session.role === "MEMBER";
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">{t("calendarTitle")}</h1>
      <WorkOrdersNav current="calendar" canEdit={canEdit} />
      <DayCalendar
        day={day && /^\d{4}-\d{2}-\d{2}$/.test(day) ? day : todayInGermany()}
        staff={staff}
        canEdit={canEdit}
      />
    </div>
  );
}
