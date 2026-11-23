import type { Metadata } from "next";
import { LegalPage } from "@/components/LegalPage";
import { impressum } from "@/content/legal/impressum";

export const metadata: Metadata = { title: `${impressum.title} – ERIKA` };

export default function Page() {
  return <LegalPage doc={impressum} />;
}
