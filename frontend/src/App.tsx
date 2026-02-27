import { useCallback, useState } from "react";
import { useSession } from "./hooks/useSession";
import { useChat } from "./hooks/useChat";
import { useWebSocket } from "./hooks/useWebSocket";
import { AppLayout } from "./components/layout/AppLayout";
import { PipelineView } from "./components/pipeline/PipelineView";
import { MessageSquare, GitBranch } from "lucide-react";

type Tab = "chat" | "pipeline";

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>("chat");

  const {
    sessions,
    activeSessionId,
    setActiveSessionId,
    createSession,
    updateSessionTitle,
    deleteSession,
  } = useSession();

  const { messages, isProcessing, sendMessage, handleEvent, clearMessages } =
    useChat(activeSessionId);

  const { status, send } = useWebSocket(activeSessionId, handleEvent);

  const handleSend = useCallback(
    (text: string) => {
      sendMessage(text, send);
      // Update session title from first message
      const session = sessions.find((s) => s.id === activeSessionId);
      if (session?.title === "New Chat") {
        updateSessionTitle(activeSessionId, text.slice(0, 50));
      }
    },
    [sendMessage, send, sessions, activeSessionId, updateSessionTitle]
  );

  const handleNewChat = useCallback(() => {
    createSession();
    clearMessages();
  }, [createSession, clearMessages]);

  const handleSelectSession = useCallback(
    (id: string) => {
      setActiveSessionId(id);
      clearMessages();
    },
    [setActiveSessionId, clearMessages]
  );

  return (
    <div className="flex h-screen overflow-hidden bg-[#0f0f0f]">
      {/* Tab sidebar */}
      <div className="flex flex-col items-center w-12 bg-[#0a0a0a] border-r border-[#1e1e1e] py-3 gap-2">
        <button
          onClick={() => setActiveTab("chat")}
          className={`p-2 rounded-lg transition-colors ${
            activeTab === "chat"
              ? "bg-[#1a1a1a] text-white"
              : "text-gray-600 hover:text-gray-400"
          }`}
          title="Chat"
        >
          <MessageSquare size={20} />
        </button>
        <button
          onClick={() => setActiveTab("pipeline")}
          className={`p-2 rounded-lg transition-colors ${
            activeTab === "pipeline"
              ? "bg-[#1a1a1a] text-white"
              : "text-gray-600 hover:text-gray-400"
          }`}
          title="Pipeline"
        >
          <GitBranch size={20} />
        </button>
      </div>

      {/* Content area */}
      <div className="flex-1 min-w-0">
        {activeTab === "chat" ? (
          <AppLayout
            sessions={sessions}
            activeSessionId={activeSessionId}
            wsStatus={status}
            messages={messages}
            isProcessing={isProcessing}
            onSend={handleSend}
            onNewChat={handleNewChat}
            onSelectSession={handleSelectSession}
            onDeleteSession={deleteSession}
          />
        ) : (
          <PipelineView />
        )}
      </div>
    </div>
  );
}
