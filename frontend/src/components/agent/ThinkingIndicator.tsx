import { Brain } from "lucide-react";

interface Props {
  thought: string;
}

export function ThinkingIndicator({ thought }: Props) {
  return (
    <div className="flex items-start gap-2 rounded-xl bg-surface/50 border border-surface-border px-4 py-3 text-sm">
      <Brain size={16} className="mt-0.5 shrink-0 text-purple-400 animate-pulse-dot" />
      <div>
        <span className="text-xs font-medium text-purple-400">Thinking...</span>
        {thought && (
          <p className="mt-1 text-xs text-gray-500 line-clamp-2">{thought}</p>
        )}
      </div>
    </div>
  );
}
