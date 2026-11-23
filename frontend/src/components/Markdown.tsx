import Link from "next/link";
import type { ReactNode } from "react";

/**
 * Renders the small Markdown subset of the legal texts (src/content/legal): headings (##, ###),
 * paragraphs, bullet and numbered lists, **bold** and [links](/path). No HTML is passed
 * through, so the content can never inject markup.
 */
export function Markdown({ source }: { source: string }) {
  const blocks = source.trim().split(/\n\s*\n/);
  return <>{blocks.map((block, i) => renderBlock(block.trim(), i))}</>;
}

function renderBlock(block: string, key: number): ReactNode {
  if (block.startsWith("### ")) {
    return (
      <h3 key={key} className="mt-6 text-lg font-semibold">
        {inline(block.slice(4))}
      </h3>
    );
  }
  if (block.startsWith("## ")) {
    return (
      <h2 key={key} className="mt-8 text-xl font-semibold">
        {inline(block.slice(3))}
      </h2>
    );
  }
  const lines = block.split("\n").map((l) => l.trim());
  if (lines.every((l) => l.startsWith("- "))) {
    return (
      <ul key={key} className="ml-6 list-disc space-y-1">
        {lines.map((l, j) => (
          <li key={j}>{inline(l.slice(2))}</li>
        ))}
      </ul>
    );
  }
  if (lines.every((l) => /^\d+\. /.test(l))) {
    return (
      <ol key={key} className="ml-6 list-decimal space-y-1">
        {lines.map((l, j) => (
          <li key={j}>{inline(l.replace(/^\d+\. /, ""))}</li>
        ))}
      </ol>
    );
  }
  return (
    <p key={key} className="mt-3">
      {inline(lines.join(" "))}
    </p>
  );
}

function inline(text: string): ReactNode[] {
  const parts: ReactNode[] = [];
  const pattern = /\*\*(.+?)\*\*|\[(.+?)\]\((.+?)\)/g;
  let last = 0;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > last) parts.push(text.slice(last, match.index));
    if (match[1] !== undefined) {
      parts.push(<strong key={match.index}>{match[1]}</strong>);
    } else {
      const href = match[3]!;
      parts.push(
        href.startsWith("/") ? (
          <Link key={match.index} href={href} className="text-brand-700 underline">
            {match[2]}
          </Link>
        ) : (
          <a key={match.index} href={href} className="text-brand-700 underline" rel="noopener noreferrer">
            {match[2]}
          </a>
        ),
      );
    }
    last = pattern.lastIndex;
  }
  if (last < text.length) parts.push(text.slice(last));
  return parts;
}
