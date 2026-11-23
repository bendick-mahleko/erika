import type { Metadata } from "next";
import { LegalPage } from "@/components/LegalPage";
import { datenschutz } from "@/content/legal/datenschutz";

export const metadata: Metadata = { title: `${datenschutz.title} – ERIKA` };

export default function Page() {
  return <LegalPage doc={datenschutz} />;
}
