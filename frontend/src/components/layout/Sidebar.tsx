import type { Session } from "../../types/api";
import { Plus, MessageSquare, Trash2 } from "lucide-react";

interface Props {
  sessions: Session[];
  activeSessionId: string;
  onNewChat: () => void;
  onSelectSession: (id: string) => void;
  onDeleteSession: (id: string) => void;
}

export function Sidebar({ sessions, activeSessionId, onNewChat, onSelectSession, onDeleteSession }: Props) {
  return (
    <div className="flex h-full flex-col bg-surface border-r border-surface-border">
      {/* Header */}
      <div className="p-4 border-b border-surface-border">
        <button
          onClick={onNewChat}
          className="flex w-full items-center gap-2 rounded-lg bg-brand px-4 py-2.5 text-sm font-medium text-white hover:bg-blue-600 transition-colors"
        >
          <Plus size={16} />
          New Chat
        </button>
      </div>

      {/* Session list */}
      <div className="flex-1 overflow-y-auto p-2">
        {sessions.map((session) => (
          <div
            key={session.id}
            className={`group flex items-center gap-2 rounded-lg px-3 py-2 text-sm cursor-pointer transition-colors ${
              session.id === activeSessionId
                ? "bg-surface-hover text-white"
                : "text-gray-400 hover:bg-surface-hover hover:text-gray-200"
            }`}
            onClick={() => onSelectSession(session.id)}
          >
            <MessageSquare size={14} className="shrink-0" />
            <span className="flex-1 truncate">{session.title}</span>
            <button
              onClick={(e) => {
                e.stopPropagation();
                onDeleteSession(session.id);
              }}
              className="hidden group-hover:block text-gray-500 hover:text-red-400"
            >
              <Trash2 size={14} />
            </button>
          </div>
        ))}
      </div>

      {/* Footer */}
      <div className="p-4 border-t border-surface-border text-xs text-gray-500">
        KAI Agent v0.1.0
      </div>
    </div>
  );
}
