import type { Metadata } from "next";
import { LegalPage } from "@/components/LegalPage";
import { maraMitarbeiterinformation } from "@/content/legal/maraMitarbeiterinformation";

export const metadata: Metadata = { title: `${maraMitarbeiterinformation.title} – ERIKA` };

export default function Page() {
  return <LegalPage doc={maraMitarbeiterinformation} />;
}
