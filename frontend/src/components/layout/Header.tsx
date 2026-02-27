import type { ReactNode } from "react";
import type { WSStatus } from "../../lib/websocket";
import { Bot, Wifi, WifiOff } from "lucide-react";

interface Props {
  wsStatus: WSStatus;
  children?: ReactNode;
}

const statusConfig: Record<WSStatus, { color: string; label: string }> = {
  connected: { color: "bg-green-500", label: "Connected" },
  connecting: { color: "bg-yellow-500 animate-pulse", label: "Connecting..." },
  disconnected: { color: "bg-gray-500", label: "Disconnected" },
  error: { color: "bg-red-500", label: "Error" },
};

export function Header({ wsStatus, children }: Props) {
  const s = statusConfig[wsStatus];
  const Icon = wsStatus === "connected" ? Wifi : WifiOff;

  return (
    <header className="flex items-center gap-3 border-b border-surface-border bg-surface px-4 py-3">
      {children}

      <Bot size={24} className="text-brand" />
      <h1 className="text-lg font-semibold text-white">KAI</h1>
      <span className="text-xs text-gray-500">AI Code Agent</span>

      <div className="ml-auto flex items-center gap-2 text-xs text-gray-400">
        <Icon size={14} />
        <span className={`inline-block h-2 w-2 rounded-full ${s.color}`} />
        {s.label}
      </div>
    </header>
  );
}
