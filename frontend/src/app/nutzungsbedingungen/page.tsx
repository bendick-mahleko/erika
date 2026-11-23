import type { Metadata } from "next";
import { LegalPage } from "@/components/LegalPage";
import { nutzungsbedingungen } from "@/content/legal/nutzungsbedingungen";

export const metadata: Metadata = { title: `${nutzungsbedingungen.title} – ERIKA` };

export default function Page() {
  return <LegalPage doc={nutzungsbedingungen} />;
}
