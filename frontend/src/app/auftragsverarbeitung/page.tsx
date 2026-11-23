import type { Metadata } from "next";
import { LegalPage } from "@/components/LegalPage";
import { auftragsverarbeitung } from "@/content/legal/auftragsverarbeitung";

export const metadata: Metadata = { title: `${auftragsverarbeitung.title} – ERIKA` };

export default function Page() {
  return <LegalPage doc={auftragsverarbeitung} />;
}
