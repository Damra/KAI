import { useState } from "react";
import type { Session, ChatMessage } from "../../types/api";
import type { WSStatus } from "../../lib/websocket";
import { Sidebar } from "./Sidebar";
import { Header } from "./Header";
import { ChatContainer } from "../chat/ChatContainer";
import { ChatInput } from "../chat/ChatInput";
import { Menu } from "lucide-react";

interface Props {
  sessions: Session[];
  activeSessionId: string;
  wsStatus: WSStatus;
  messages: ChatMessage[];
  isProcessing: boolean;
  onSend: (text: string) => void;
  onNewChat: () => void;
  onSelectSession: (id: string) => void;
  onDeleteSession: (id: string) => void;
}

export function AppLayout({
  sessions,
  activeSessionId,
  wsStatus,
  messages,
  isProcessing,
  onSend,
  onNewChat,
  onSelectSession,
  onDeleteSession,
}: Props) {
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <div className="flex h-screen overflow-hidden bg-[#0f0f0f]">
      {/* Mobile sidebar overlay */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/50 md:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* Sidebar */}
      <div
        className={`fixed inset-y-0 left-0 z-50 w-64 transform transition-transform md:relative md:translate-x-0 ${
          sidebarOpen ? "translate-x-0" : "-translate-x-full"
        }`}
      >
        <Sidebar
          sessions={sessions}
          activeSessionId={activeSessionId}
          onNewChat={onNewChat}
          onSelectSession={(id) => {
            onSelectSession(id);
            setSidebarOpen(false);
          }}
          onDeleteSession={onDeleteSession}
        />
      </div>

      {/* Main content */}
      <div className="flex flex-1 flex-col min-w-0">
        <Header wsStatus={wsStatus}>
          <button
            onClick={() => setSidebarOpen(true)}
            className="md:hidden p-2 text-gray-400 hover:text-white"
          >
            <Menu size={20} />
          </button>
        </Header>

        <ChatContainer messages={messages} />

        <ChatInput onSend={onSend} disabled={isProcessing || wsStatus !== "connected"} />
      </div>
    </div>
  );
}
