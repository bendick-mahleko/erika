import type { Metadata } from "next";
import { LegalPage } from "@/components/LegalPage";
import { maraBetriebsrat } from "@/content/legal/maraBetriebsrat";

export const metadata: Metadata = { title: `${maraBetriebsrat.title} – ERIKA` };

export default function Page() {
  return <LegalPage doc={maraBetriebsrat} />;
}
