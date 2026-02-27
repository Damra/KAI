import { useState } from "react";
import { Wrench, ChevronDown, ChevronRight, CheckCircle, XCircle, Loader2 } from "lucide-react";

interface Props {
  tool: string;
  input: string;
  output?: string;
  success?: boolean;
}

export function ToolCallCard({ tool, input, output, success }: Props) {
  const [expanded, setExpanded] = useState(false);
  const isRunning = success === undefined;

  return (
    <div className="rounded-lg border border-surface-border bg-surface/50 text-xs">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-2 px-3 py-2 text-left"
      >
        <Wrench size={12} className="text-yellow-500" />
        <span className="flex-1 font-medium text-gray-300">{tool}</span>
        {isRunning && <Loader2 size={12} className="animate-spin text-blue-400" />}
        {success === true && <CheckCircle size={12} className="text-green-400" />}
        {success === false && <XCircle size={12} className="text-red-400" />}
        {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
      </button>

      {expanded && (
        <div className="border-t border-surface-border px-3 py-2 space-y-2">
          <div>
            <span className="text-gray-500">Input:</span>
            <pre className="mt-1 overflow-x-auto rounded bg-[#111] p-2 text-gray-400 whitespace-pre-wrap">
              {input.length > 500 ? input.slice(0, 500) + "..." : input}
            </pre>
          </div>
          {output && (
            <div>
              <span className="text-gray-500">Output:</span>
              <pre className="mt-1 overflow-x-auto rounded bg-[#111] p-2 text-gray-400 whitespace-pre-wrap">
                {output.length > 500 ? output.slice(0, 500) + "..." : output}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
