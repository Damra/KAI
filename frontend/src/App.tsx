import { useCallback } from "react";
import { useSession } from "./hooks/useSession";
import { useChat } from "./hooks/useChat";
import { useWebSocket } from "./hooks/useWebSocket";
import { AppLayout } from "./components/layout/AppLayout";

export default function App() {
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
  );
}
