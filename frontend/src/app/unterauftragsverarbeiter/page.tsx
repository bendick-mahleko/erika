import type { Metadata } from "next";
import { LegalPage } from "@/components/LegalPage";
import { unterauftragsverarbeiter } from "@/content/legal/unterauftragsverarbeiter";

export const metadata: Metadata = { title: `${unterauftragsverarbeiter.title} – ERIKA` };

export default function Page() {
  return <LegalPage doc={unterauftragsverarbeiter} />;
}
