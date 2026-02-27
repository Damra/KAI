interface Props {
  content: string;
  isStreaming: boolean;
}

export function StreamingMessage({ content, isStreaming }: Props) {
  // Simple markdown-like rendering: split by code blocks
  const parts = content.split(/(```[\s\S]*?```)/g);

  return (
    <div className="rounded-2xl rounded-tl-md bg-surface px-4 py-3 text-sm text-gray-200 leading-relaxed">
      {parts.map((part, i) => {
        if (part.startsWith("```")) {
          const lines = part.slice(3, -3).split("\n");
          const lang = lines[0]?.trim() || "";
          const code = lang ? lines.slice(1).join("\n") : lines.join("\n");
          return (
            <pre key={i} className="my-2 overflow-x-auto rounded-lg bg-[#111] p-3 text-xs text-gray-300">
              {lang && <div className="mb-1 text-[10px] uppercase text-gray-500">{lang}</div>}
              <code>{code}</code>
            </pre>
          );
        }
        // Render paragraphs
        return (
          <span key={i} className="whitespace-pre-wrap">
            {part}
          </span>
        );
      })}
      {isStreaming && (
        <span className="inline-block h-4 w-1.5 animate-pulse bg-brand ml-1 rounded-sm" />
      )}
    </div>
  );
}
